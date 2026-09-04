package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.ModelCatalogEntryResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ModelCatalogService {

    private final ModelCatalogRepository repository;
    private final RuntimeModelStatusService runtimeStatusService;
    private final AuditEventService audit;
    private final Clock clock;

    public ModelCatalogService(
            ModelCatalogRepository repository,
            RuntimeModelStatusService runtimeStatusService,
            AuditEventService audit,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository не должен быть null"
        );
        this.runtimeStatusService = Objects.requireNonNull(
                runtimeStatusService,
                "runtimeStatusService не должен быть null"
        );
        this.audit = Objects.requireNonNull(
                audit,
                "audit не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @Transactional(readOnly = true)
    public List<ModelCatalogEntryResponse> findLatest(
            SafeAiUserPrincipal currentUser
    ) {
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для просмотра model catalog"
        );

        return repository.findLatestAll()
                .stream()
                .map(ModelCatalogEntryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ModelCatalogEntryResponse> findEffective(
            SafeAiUserPrincipal currentUser
    ) {
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для просмотра model catalog"
        );

        Instant now =
                clock.instant();

        return repository.findEffectiveAll(now)
                .stream()
                .map(ModelCatalogEntryResponse::from)
                .toList();
    }

    @Transactional
    public ModelCatalogEntryResponse createVersion(
            CreateModelCatalogVersionRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ModelControlPlaneAccess.requireSuperAdmin(
                currentUser
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        String modelKey =
                normalizeModelKey(
                        request.modelKey()
                );

        String provider =
                ModelCatalogRules.normalizeProvider(
                        request.provider()
                );

        String providerModelId =
                ModelCatalogRules.normalizeRequired(
                        request.providerModelId(),
                        100,
                        "providerModelId"
                );

        String displayName =
                ModelCatalogRules.normalizeRequired(
                        request.displayName(),
                        255,
                        "displayName"
                );

        String extraPricingJson =
                ModelCatalogRules.validateExtraPricingJson(
                        request.extraPricingJson()
                );

        String pricingVersion =
                ModelCatalogRules.normalizePricingVersion(
                        request.pricingVersion()
                );

        BigDecimal inputPrice =
                ModelCatalogRules.normalizeMoney(
                        request.inputUsdPer1mTokens(),
                        "inputUsdPer1mTokens"
                );

        BigDecimal cachedInputPrice =
                ModelCatalogRules.normalizeMoney(
                        request.cachedInputUsdPer1mTokens(),
                        "cachedInputUsdPer1mTokens"
                );

        BigDecimal cacheWriteInputPrice =
                ModelCatalogRules.normalizeMoney(
                        request.cacheWriteInputUsdPer1mTokens(),
                        "cacheWriteInputUsdPer1mTokens"
                );

        BigDecimal outputPrice =
                ModelCatalogRules.normalizeMoney(
                        request.outputUsdPer1mTokens(),
                        "outputUsdPer1mTokens"
                );

        Set<ModelCapability> capabilities =
                request.capabilities() == null
                        ? Set.of()
                        : Set.copyOf(
                                request.capabilities()
                        );

        Set<ModelModality> inputModalities =
                request.inputModalities() == null
                        ? Set.of(
                                ModelModality.TEXT
                        )
                        : Set.copyOf(
                                request.inputModalities()
                        );

        Set<ModelModality> outputModalities =
                request.outputModalities() == null
                        ? Set.of(
                                ModelModality.TEXT
                        )
                        : Set.copyOf(
                                request.outputModalities()
                        );

        ModelCatalogRules.validateCatalogSemantics(
                request.lifecycle(),
                request.maxInputTokens(),
                request.maxOutputTokens(),
                inputModalities,
                outputModalities,
                request.retentionStatus(),
                request.retentionDays(),
                request.trainingUseStatus(),
                request.pricingStatus(),
                request.pricingComplete(),
                inputPrice,
                cachedInputPrice,
                cacheWriteInputPrice,
                outputPrice,
                pricingVersion,
                extraPricingJson
        );

        repository.lockModelKey(
                modelKey
        );

        int previousVersion =
                repository.findLatest(
                                modelKey
                        )
                        .map(
                                ModelCatalogEntry::version
                        )
                        .orElse(0);

        if (previousVersion
                != request.expectedPreviousVersion()) {
            throw new ConflictException(
                    "Model catalog version conflict: expected previous version "
                            + request.expectedPreviousVersion()
                            + ", actual "
                            + previousVersion
            );
        }

        Instant now =
                clock.instant();

        ModelCatalogEntry entry =
                new ModelCatalogEntry(
                        UUID.randomUUID(),
                        modelKey,
                        previousVersion + 1,
                        provider,
                        providerModelId,
                        displayName,
                        request.lifecycle(),
                        request.maxInputTokens(),
                        request.maxOutputTokens(),
                        capabilities,
                        inputModalities,
                        outputModalities,
                        request.retentionStatus(),
                        request.retentionDays(),
                        request.trainingUseStatus(),
                        request.pricingStatus(),
                        request.pricingComplete(),
                        inputPrice,
                        cachedInputPrice,
                        cacheWriteInputPrice,
                        outputPrice,
                        extraPricingJson,
                        pricingVersion,
                        request.effectiveFrom() == null
                                ? now
                                : request.effectiveFrom(),
                        ModelCatalogSource.MANUAL,
                        currentUser.getId(),
                        now
                );

        repository.insert(
                entry
        );

        recordCatalogAudit(
                currentUser,
                entry
        );

        return ModelCatalogEntryResponse.from(
                entry
        );
    }
    /**
     * Bootstraps the catalog from the physical runtime without overstating
     * retention/training/specialized pricing metadata.
     *
     * <p>The operation is idempotent for an unchanged latest RUNTIME_IMPORT:
     * repeated button clicks/API retries return the existing immutable
     * snapshot instead of manufacturing v2/v3/v4 copies.</p>
     */
    @Transactional
    public ModelCatalogEntryResponse importRuntime(
            SafeAiUserPrincipal currentUser
    ) {
        ModelControlPlaneAccess.requireSuperAdmin(
                currentUser
        );

        RuntimeModelStatusResponse runtime =
                Objects.requireNonNull(
                        runtimeStatusService.current(),
                        "Runtime model status не должен быть null"
                );

        if (!runtime.enabled()) {
            throw new ConflictException(
                    "Нельзя импортировать отключённый runtime model"
            );
        }

        String runtimeProvider =
                ModelCatalogRules.normalizeProvider(
                        runtime.provider()
                );

        String runtimeModel =
                ModelCatalogRules.normalizeRequired(
                        runtime.model(),
                        100,
                        "runtime.model"
                );

        String modelKey =
                normalizeModelKey(
                        runtimeProvider
                                + ":"
                                + runtimeModel
                );

        String runtimePricingStatus =
                Objects.requireNonNull(
                        runtime.pricingStatus(),
                        "runtime.pricingStatus не должен быть null"
                );

        ModelPricingStatus pricingStatus;
        boolean pricingComplete;

        switch (runtimePricingStatus) {
            case "FREE" -> {
                pricingStatus =
                        ModelPricingStatus.FREE;
                pricingComplete =
                        true;
            }
            case "CONFIGURED" -> {
                /*
                 * The legacy runtime exposes only ordinary input/output prices.
                 * Cached input, cache-write and extra dimensions are not proven.
                 */
                pricingStatus =
                        ModelPricingStatus.INCOMPLETE;
                pricingComplete =
                        false;
            }
            case "UNPRICED" -> {
                pricingStatus =
                        ModelPricingStatus.UNPRICED;
                pricingComplete =
                        false;
            }
            default ->
                    throw new IllegalStateException(
                            "Unsupported runtime pricing status: "
                                    + runtimePricingStatus
                    );
        }

        EnumSet<ModelCapability> capabilities =
                EnumSet.noneOf(
                        ModelCapability.class
                );

        if (runtime.toolsSupported()) {
            capabilities.add(
                    ModelCapability.TOOLS
            );
        }

        if (runtime.visionSupported()) {
            capabilities.add(
                    ModelCapability.VISION
            );
        }

        if (runtime.structuredOutputSupported()) {
            capabilities.add(
                    ModelCapability.STRUCTURED_OUTPUT
            );
        }

        EnumSet<ModelModality> inputModalities =
                EnumSet.of(
                        ModelModality.TEXT
                );

        if (runtime.visionSupported()) {
            inputModalities.add(
                    ModelModality.IMAGE
            );
        }

        Set<ModelModality> outputModalities =
                Set.of(
                        ModelModality.TEXT
                );

        BigDecimal runtimeInputPrice =
                ModelCatalogRules.normalizeMoney(
                        runtime.inputUsdPer1mTokens(),
                        "runtime.inputUsdPer1mTokens"
                );

        BigDecimal runtimeOutputPrice =
                ModelCatalogRules.normalizeMoney(
                        runtime.outputUsdPer1mTokens(),
                        "runtime.outputUsdPer1mTokens"
                );

        String runtimePricingVersion =
                ModelCatalogRules.normalizePricingVersion(
                        runtime.pricingVersion()
                );

        ModelCatalogRules.validateCatalogSemantics(
                ModelLifecycle.ACTIVE,
                runtime.maxInputTokens(),
                runtime.maxOutputTokens(),
                inputModalities,
                outputModalities,
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                pricingStatus,
                pricingComplete,
                runtimeInputPrice,
                null,
                null,
                runtimeOutputPrice,
                runtimePricingVersion,
                "{}"
        );

        repository.lockModelKey(
                modelKey
        );
        ModelCatalogEntry latest =
                repository.findLatest(
                                modelKey
                        )
                        .orElse(null);

        if (latest != null
                && latest.source()
                != ModelCatalogSource.RUNTIME_IMPORT) {
            throw new ConflictException(
                    "Модель уже управляется явной версией каталога "
                            + "(source="
                            + latest.source()
                            + ", version="
                            + latest.version()
                            + "). Создайте новую версию явно: "
                            + "import-runtime не переопределяет ручное или мигрированное governance-состояние."
            );
        }

        if (latest != null
                && sameRuntimeSnapshot(
                        latest,
                        runtimeProvider,
                        runtimeModel,
                        runtime,
                        capabilities,
                        inputModalities,
                        outputModalities,
                        pricingStatus,
                        pricingComplete,
                        runtimeInputPrice,
                        runtimeOutputPrice,
                        runtimePricingVersion
                )) {
            return ModelCatalogEntryResponse.from(
                    latest
            );
        }

        int previousVersion =
                latest == null
                        ? 0
                        : latest.version();

        Instant now =
                clock.instant();

        ModelCatalogEntry entry =
                new ModelCatalogEntry(
                        UUID.randomUUID(),
                        modelKey,
                        previousVersion + 1,
                        runtimeProvider,
                        runtimeModel,
                        runtimeModel,
                        ModelLifecycle.ACTIVE,
                        runtime.maxInputTokens(),
                        runtime.maxOutputTokens(),
                        capabilities,
                        inputModalities,
                        outputModalities,
                        ModelRetentionStatus.NOT_DECLARED,
                        null,
                        ModelTrainingUseStatus.NOT_DECLARED,
                        pricingStatus,
                        pricingComplete,
                        runtimeInputPrice,
                        null,
                        null,
                        runtimeOutputPrice,
                        "{}",
                        runtimePricingVersion,
                        now,
                        ModelCatalogSource.RUNTIME_IMPORT,
                        currentUser.getId(),
                        now
                );

        repository.insert(
                entry
        );

        recordCatalogAudit(
                currentUser,
                entry
        );

        return ModelCatalogEntryResponse.from(
                entry
        );
    }

    private static boolean sameRuntimeSnapshot(
            ModelCatalogEntry latest,
            String provider,
            String providerModelId,
            RuntimeModelStatusResponse runtime,
            Set<ModelCapability> capabilities,
            Set<ModelModality> inputModalities,
            Set<ModelModality> outputModalities,
            ModelPricingStatus pricingStatus,
            boolean pricingComplete,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            String pricingVersion
    ) {
        return latest.modelKey().equals(
                        normalizeModelKey(
                                provider
                                        + ":"
                                        + providerModelId
                        )
                )
                && latest.provider().equals(
                        provider
                )
                && latest.providerModelId().equals(
                        providerModelId
                )
                && latest.displayName().equals(
                        providerModelId
                )
                && latest.lifecycle()
                == ModelLifecycle.ACTIVE
                && latest.maxInputTokens()
                == runtime.maxInputTokens()
                && latest.maxOutputTokens()
                == runtime.maxOutputTokens()
                && latest.capabilities().equals(
                        capabilities
                )
                && latest.inputModalities().equals(
                        inputModalities
                )
                && latest.outputModalities().equals(
                        outputModalities
                )
                && latest.retentionStatus()
                == ModelRetentionStatus.NOT_DECLARED
                && latest.retentionDays()
                == null
                && latest.trainingUseStatus()
                == ModelTrainingUseStatus.NOT_DECLARED
                && latest.pricingStatus()
                == pricingStatus
                && latest.pricingComplete()
                == pricingComplete
                && moneyEquals(
                        latest.inputUsdPer1mTokens(),
                        inputPrice
                )
                && latest.cachedInputUsdPer1mTokens()
                == null
                && latest.cacheWriteInputUsdPer1mTokens()
                == null
                && moneyEquals(
                        latest.outputUsdPer1mTokens(),
                        outputPrice
                )
                && "{}".equals(
                        latest.extraPricingJson()
                )
                && Objects.equals(
                        latest.pricingVersion(),
                        pricingVersion
                );
    }

    private static boolean moneyEquals(
            BigDecimal left,
            BigDecimal right
    ) {
        if (left == null || right == null) {
            return left == right;
        }

        return left.compareTo(
                right
        ) == 0;
    }

    private void recordCatalogAudit(
            SafeAiUserPrincipal currentUser,
            ModelCatalogEntry entry
    ) {
        audit.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.MODEL_CATALOG_VERSION_CREATED,
                ModelCatalogAuditDetailsFactory.create(
                        entry
                )
        );
    }

    static String normalizeModelKey(
            String value
    ) {
        return ModelCatalogRules.normalizeModelKey(
                value
        );
    }
}
