package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

    @Mock
    private ModelCatalogRepository repository;

    @Mock
    private RuntimeModelStatusService runtimeStatusService;

    @Mock
    private AuditEventService audit;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository,
                runtimeStatusService,
                audit,
                ModelTestFixtures.CLOCK
        );
    }

    @Test
    void effectiveCatalogUsesServerClock() {
        when(repository.findEffectiveAll(ModelTestFixtures.NOW))
                .thenReturn(List.of());

        assertThat(service.findEffective(
                ModelTestFixtures.superAdminPrincipal()
        )).isEmpty();

        verify(repository)
                .findEffectiveAll(ModelTestFixtures.NOW);
    }

    @Test
    void adminCanReadCatalogButUserCannot() {
        when(repository.findLatestAll())
                .thenReturn(List.of());

        assertThat(service.findLatest(
                ModelTestFixtures.adminPrincipal()
        )).isEmpty();

        assertThatThrownBy(() -> service.findLatest(
                ModelTestFixtures.userPrincipal()
        )).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void onlySuperAdminCanCreateCatalogVersion() {
        assertThatThrownBy(() -> service.createVersion(
                validFreeRequest(),
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(ForbiddenOperationException.class);

        verify(repository, never()).insert(any());
    }

    @Test
    void createVersionNormalizesIdentityAndUsesServerClockByDefault() {
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.empty());

        CreateModelCatalogVersionRequest request = new CreateModelCatalogVersionRequest(
                " OpenAI:GPT-Test ",
                " OpenAI ",
                " gpt-test ",
                " GPT Test ",
                ModelLifecycle.ACTIVE,
                32_000,
                4_096,
                null,
                null,
                null,
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                " { } ",
                null,
                null,
                0
        );

        service.createVersion(
                request,
                ModelTestFixtures.superAdminPrincipal()
        );

        ArgumentCaptor<ModelCatalogEntry> captor =
                ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repository).insert(captor.capture());

        ModelCatalogEntry entry = captor.getValue();
        assertThat(entry.modelKey())
                .isEqualTo("openai:gpt-test");
        assertThat(entry.provider())
                .isEqualTo("openai");
        assertThat(entry.providerModelId())
                .isEqualTo("gpt-test");
        assertThat(entry.displayName())
                .isEqualTo("GPT Test");
        assertThat(entry.inputModalities())
                .containsExactly(ModelModality.TEXT);
        assertThat(entry.outputModalities())
                .containsExactly(ModelModality.TEXT);
        assertThat(entry.extraPricingJson())
                .isEqualTo("{}");
        assertThat(entry.effectiveFrom())
                .isEqualTo(ModelTestFixtures.NOW);
        assertThat(entry.createdAt())
                .isEqualTo(ModelTestFixtures.NOW);
        assertThat(entry.source())
                .isEqualTo(ModelCatalogSource.MANUAL);
        assertThat(entry.inputUsdPer1mTokens())
                .isEqualTo(
                        new BigDecimal("0.000000000000")
                );
        assertThat(entry.outputUsdPer1mTokens())
                .isEqualTo(
                        new BigDecimal("0.000000000000")
                );
    }

    @Test
    void configuredPricesAreCanonicalizedToDatabaseScaleBeforePersistence() {
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.empty());

        CreateModelCatalogVersionRequest request = pricingRequest(
                ModelPricingStatus.CONFIGURED,
                true,
                new BigDecimal("1.5"),
                new BigDecimal("1.25"),
                new BigDecimal("1.4"),
                new BigDecimal("4"),
                "{}",
                "pricing-v1"
        );

        service.createVersion(
                request,
                ModelTestFixtures.superAdminPrincipal()
        );

        ArgumentCaptor<ModelCatalogEntry> captor =
                ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repository).insert(captor.capture());

        assertThat(captor.getValue().inputUsdPer1mTokens())
                .isEqualTo(new BigDecimal("1.500000000000"));
        assertThat(captor.getValue().cachedInputUsdPer1mTokens())
                .isEqualTo(new BigDecimal("1.250000000000"));
        assertThat(captor.getValue().cacheWriteInputUsdPer1mTokens())
                .isEqualTo(new BigDecimal("1.400000000000"));
        assertThat(captor.getValue().outputUsdPer1mTokens())
                .isEqualTo(new BigDecimal("4.000000000000"));
    }

    @Test
    void scheduledEffectiveFromIsPreserved() {
        Instant scheduled = ModelTestFixtures.NOW.plusSeconds(3_600);
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.empty());

        CreateModelCatalogVersionRequest request = copy(
                validFreeRequest(),
                scheduled
        );

        service.createVersion(
                request,
                ModelTestFixtures.superAdminPrincipal()
        );

        ArgumentCaptor<ModelCatalogEntry> captor =
                ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().effectiveFrom())
                .isEqualTo(scheduled);
    }

    @Test
    void optimisticVersionConflictFailsBeforeInsert() {
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.of(ModelTestFixtures.freeEntry()));

        assertThatThrownBy(() -> service.createVersion(
                validFreeRequest(),
                ModelTestFixtures.superAdminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("actual 2");

        verify(repository, never()).insert(any());
    }

    @Test
    void unpricedRejectsAnySpecializedPriceBeforeDatabaseWrite() {
        CreateModelCatalogVersionRequest request = pricingRequest(
                ModelPricingStatus.UNPRICED,
                false,
                null,
                BigDecimal.ZERO,
                null,
                null,
                "{}",
                null
        );

        assertRejectedBeforeInsert(request);
    }

    @Test
    void freeRejectsNonZeroSpecializedPrice() {
        CreateModelCatalogVersionRequest request = pricingRequest(
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                new BigDecimal("0.01"),
                null,
                BigDecimal.ZERO,
                "{}",
                null
        );

        assertRejectedBeforeInsert(request);
    }

    @Test
    void configuredRequiresPricingVersion() {
        CreateModelCatalogVersionRequest request = pricingRequest(
                ModelPricingStatus.CONFIGURED,
                true,
                BigDecimal.ONE,
                null,
                null,
                new BigDecimal("4"),
                "{}",
                null
        );

        assertRejectedBeforeInsert(request);
    }

    @Test
    void configuredRejectsCachedPriceAboveOrdinaryInputPrice() {
        assertRejectedBeforeInsert(pricingRequest(
                ModelPricingStatus.CONFIGURED,
                true,
                new BigDecimal("1.00"),
                new BigDecimal("1.01"),
                null,
                new BigDecimal("4.00"),
                "{}",
                "pricing-2026-08"
        ));
    }

    @Test
    void configuredRejectsCacheWritePriceAboveOrdinaryInputPrice() {
        assertRejectedBeforeInsert(pricingRequest(
                ModelPricingStatus.CONFIGURED,
                true,
                new BigDecimal("1.00"),
                null,
                new BigDecimal("1.01"),
                new BigDecimal("4.00"),
                "{}",
                "pricing-2026-08"
        ));
    }

    @Test
    void incompleteCannotClaimCompletePricing() {
        assertRejectedBeforeInsert(pricingRequest(
                ModelPricingStatus.INCOMPLETE,
                true,
                BigDecimal.ONE,
                null,
                null,
                new BigDecimal("4"),
                "{}",
                null
        ));
    }

    @Test
    void rejectsNumericScaleThatCannotFitDatabase() {
        assertRejectedBeforeInsert(pricingRequest(
                ModelPricingStatus.INCOMPLETE,
                false,
                new BigDecimal("0.0000000000001"),
                null,
                null,
                null,
                "{}",
                null
        ));
    }

    @Test
    void zeroDataRetentionRejectsPositiveRetentionDays() {
        CreateModelCatalogVersionRequest base = validFreeRequest();
        CreateModelCatalogVersionRequest invalid =
                new CreateModelCatalogVersionRequest(
                        base.modelKey(),
                        base.provider(),
                        base.providerModelId(),
                        base.displayName(),
                        base.lifecycle(),
                        base.maxInputTokens(),
                        base.maxOutputTokens(),
                        base.capabilities(),
                        base.inputModalities(),
                        base.outputModalities(),
                        ModelRetentionStatus.ZERO_DATA_RETENTION,
                        1,
                        base.trainingUseStatus(),
                        base.pricingStatus(),
                        base.pricingComplete(),
                        base.inputUsdPer1mTokens(),
                        base.cachedInputUsdPer1mTokens(),
                        base.cacheWriteInputUsdPer1mTokens(),
                        base.outputUsdPer1mTokens(),
                        base.extraPricingJson(),
                        base.pricingVersion(),
                        base.effectiveFrom(),
                        base.expectedPreviousVersion()
                );

        assertRejectedBeforeInsert(invalid);
    }

    @Test
    void imageOutputModalityIsRejectedBeforeDatabaseWrite() {
        CreateModelCatalogVersionRequest base = validFreeRequest();
        CreateModelCatalogVersionRequest invalid =
                new CreateModelCatalogVersionRequest(
                        base.modelKey(),
                        base.provider(),
                        base.providerModelId(),
                        base.displayName(),
                        base.lifecycle(),
                        base.maxInputTokens(),
                        base.maxOutputTokens(),
                        base.capabilities(),
                        base.inputModalities(),
                        Set.of(ModelModality.IMAGE),
                        base.retentionStatus(),
                        base.retentionDays(),
                        base.trainingUseStatus(),
                        base.pricingStatus(),
                        base.pricingComplete(),
                        base.inputUsdPer1mTokens(),
                        base.cachedInputUsdPer1mTokens(),
                        base.cacheWriteInputUsdPer1mTokens(),
                        base.outputUsdPer1mTokens(),
                        base.extraPricingJson(),
                        base.pricingVersion(),
                        base.effectiveFrom(),
                        base.expectedPreviousVersion()
                );

        assertThatThrownBy(() -> service.createVersion(
                invalid,
                ModelTestFixtures.superAdminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("IMAGE output modality");

        verify(repository, never()).insert(any());
    }

    @Test
    void nonObjectPricingJsonIsRejected() {
        CreateModelCatalogVersionRequest request = pricingRequest(
                ModelPricingStatus.INCOMPLETE,
                false,
                null,
                null,
                null,
                null,
                "[]",
                null
        );

        assertRejectedBeforeInsert(request);
    }

    @Test
    void invalidPricingJsonIsRejected() {
        CreateModelCatalogVersionRequest request = pricingRequest(
                ModelPricingStatus.INCOMPLETE,
                false,
                null,
                null,
                null,
                null,
                "{",
                null
        );

        assertRejectedBeforeInsert(request);
    }

    @Test
    void importFreeRuntimeCreatesTruthfulCompleteFreeSnapshot() {
        when(runtimeStatusService.current())
                .thenReturn(ModelTestFixtures.freeRuntime());
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.empty());

        service.importRuntime(
                ModelTestFixtures.superAdminPrincipal()
        );

        ArgumentCaptor<ModelCatalogEntry> captor =
                ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repository).insert(captor.capture());
        ModelCatalogEntry entry = captor.getValue();

        assertThat(entry.pricingStatus())
                .isEqualTo(ModelPricingStatus.FREE);
        assertThat(entry.pricingComplete())
                .isTrue();
        assertThat(entry.source())
                .isEqualTo(ModelCatalogSource.RUNTIME_IMPORT);
    }

    @Test
    void importConfiguredLegacyRuntimeDoesNotOverstatePricingCompleteness() {
        when(runtimeStatusService.current())
                .thenReturn(ModelTestFixtures.configuredRuntime());
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.empty());

        service.importRuntime(
                ModelTestFixtures.superAdminPrincipal()
        );

        ArgumentCaptor<ModelCatalogEntry> captor =
                ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repository).insert(captor.capture());

        assertThat(captor.getValue().pricingStatus())
                .isEqualTo(ModelPricingStatus.INCOMPLETE);
        assertThat(captor.getValue().pricingComplete())
                .isFalse();
    }

    @Test
    void disabledRuntimeCannotBeImportedAsActiveCatalogEntry() {
        RuntimeModelStatusResponse runtime = new RuntimeModelStatusResponse(
                "openai",
                "gpt-test",
                false,
                "SINGLE_PROVIDER_STATIC",
                32_000,
                4_096,
                false,
                false,
                false,
                "NOT_DECLARED",
                "NOT_PROBED",
                "UNPRICED",
                null,
                null,
                null
        );
        when(runtimeStatusService.current())
                .thenReturn(runtime);

        assertThatThrownBy(() -> service.importRuntime(
                ModelTestFixtures.superAdminPrincipal()
        )).isInstanceOf(ConflictException.class);

        verify(repository, never()).insert(any());
    }

    @Test
    void importRuntimeRejectsUnknownPricingStatusFailClosed() {
        RuntimeModelStatusResponse runtime =
                new RuntimeModelStatusResponse(
                        "openai",
                        "gpt-test",
                        true,
                        "SINGLE_PROVIDER_STATIC",
                        32_000,
                        4_096,
                        false,
                        false,
                        false,
                        "NOT_DECLARED",
                        "NOT_PROBED",
                        "UNKNOWN",
                        null,
                        null,
                        null
                );
        when(runtimeStatusService.current())
                .thenReturn(runtime);

        assertThatThrownBy(() -> service.importRuntime(
                ModelTestFixtures.superAdminPrincipal()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported runtime pricing status");

        verify(repository, never()).insert(any());
    }

    @Test
    void importRuntimeMapsVisionCapabilityToImageInputModality() {
        RuntimeModelStatusResponse runtime =
                new RuntimeModelStatusResponse(
                        "openai",
                        "gpt-vision",
                        true,
                        "SINGLE_PROVIDER_STATIC",
                        32_000,
                        4_096,
                        false,
                        true,
                        false,
                        "NOT_DECLARED",
                        "NOT_PROBED",
                        "FREE",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                );
        when(runtimeStatusService.current())
                .thenReturn(runtime);
        when(repository.findLatest("openai:gpt-vision"))
                .thenReturn(Optional.empty());

        service.importRuntime(
                ModelTestFixtures.superAdminPrincipal()
        );

        ArgumentCaptor<ModelCatalogEntry> captor =
                ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repository).insert(captor.capture());

        assertThat(captor.getValue().capabilities())
                .contains(ModelCapability.VISION);
        assertThat(captor.getValue().inputModalities())
                .containsExactly(
                        ModelModality.TEXT,
                        ModelModality.IMAGE
                );
        assertThat(captor.getValue().outputModalities())
                .containsExactly(ModelModality.TEXT);
    }

    @Test
    void catalogCreationWritesUsefulGovernanceAuditSnapshot() {
        when(repository.findLatest("openai:gpt-test"))
                .thenReturn(Optional.empty());

        service.createVersion(
                validFreeRequest(),
                ModelTestFixtures.superAdminPrincipal()
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(audit).record(
                eq(ModelTestFixtures.superAdminPrincipal()),
                eq(ModelTestFixtures.ORGANIZATION_ID),
                eq(AuditEventType.MODEL_CATALOG_VERSION_CREATED),
                detailsCaptor.capture()
        );

        assertThat(detailsCaptor.getValue())
                .containsKeys(
                        "catalogEntryId",
                        "modelKey",
                        "version",
                        "provider",
                        "providerModelId",
                        "pricingComplete",
                        "maxInputTokens",
                        "maxOutputTokens",
                        "effectiveFrom"
                );
    }

    private void assertRejectedBeforeInsert(
            CreateModelCatalogVersionRequest request
    ) {
        assertThatThrownBy(() -> service.createVersion(
                request,
                ModelTestFixtures.superAdminPrincipal()
        )).isInstanceOf(BadRequestException.class);

        verify(repository, never()).insert(any());
    }

    private static CreateModelCatalogVersionRequest validFreeRequest() {
        return new CreateModelCatalogVersionRequest(
                "openai:gpt-test",
                "openai",
                "gpt-test",
                "GPT Test",
                ModelLifecycle.ACTIVE,
                32_000,
                4_096,
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                "{}",
                null,
                null,
                0
        );
    }

    private static CreateModelCatalogVersionRequest pricingRequest(
            ModelPricingStatus pricingStatus,
            boolean pricingComplete,
            BigDecimal input,
            BigDecimal cachedInput,
            BigDecimal cacheWrite,
            BigDecimal output,
            String extraPricingJson,
            String pricingVersion
    ) {
        CreateModelCatalogVersionRequest base = validFreeRequest();
        return new CreateModelCatalogVersionRequest(
                base.modelKey(),
                base.provider(),
                base.providerModelId(),
                base.displayName(),
                base.lifecycle(),
                base.maxInputTokens(),
                base.maxOutputTokens(),
                base.capabilities(),
                base.inputModalities(),
                base.outputModalities(),
                base.retentionStatus(),
                base.retentionDays(),
                base.trainingUseStatus(),
                pricingStatus,
                pricingComplete,
                input,
                cachedInput,
                cacheWrite,
                output,
                extraPricingJson,
                pricingVersion,
                base.effectiveFrom(),
                base.expectedPreviousVersion()
        );
    }

    private static CreateModelCatalogVersionRequest copy(
            CreateModelCatalogVersionRequest source,
            Instant effectiveFrom
    ) {
        return new CreateModelCatalogVersionRequest(
                source.modelKey(),
                source.provider(),
                source.providerModelId(),
                source.displayName(),
                source.lifecycle(),
                source.maxInputTokens(),
                source.maxOutputTokens(),
                source.capabilities(),
                source.inputModalities(),
                source.outputModalities(),
                source.retentionStatus(),
                source.retentionDays(),
                source.trainingUseStatus(),
                source.pricingStatus(),
                source.pricingComplete(),
                source.inputUsdPer1mTokens(),
                source.cachedInputUsdPer1mTokens(),
                source.cacheWriteInputUsdPer1mTokens(),
                source.outputUsdPer1mTokens(),
                source.extraPricingJson(),
                source.pricingVersion(),
                effectiveFrom,
                source.expectedPreviousVersion()
        );
    }
}
