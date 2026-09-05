package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ModelRoutingSelectionPolicy {

    private final ModelCatalogRepository catalogRepository;

    ModelRoutingSelectionPolicy(
            ModelCatalogRepository catalogRepository
    ) {
        this.catalogRepository = Objects.requireNonNull(
                catalogRepository,
                "catalogRepository не должен быть null"
        );
    }

    Selection selectCatalog(
            ModelRouteRequest request,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            Instant now
    ) {
        String requested =
                normalizeNullableKey(request.requestedModelKey());

        String policyDefault =
                policyEnabled
                        ? policy.defaultModelKey()
                        : null;

        String explicit =
                requested != null
                        ? requested
                        : policyDefault;

        if (explicit != null) {
            ModelCatalogEntry entry =
                    catalogRepository
                            .findEffective(explicit, now)
                            .orElse(null);

            if (entry == null) {
                return Selection.modelNotFound(explicit);
            }

            return Selection.resolved(
                    entry,
                    explicit,
                    requested != null
                            ? ModelRouteReason.REQUESTED_MODEL
                            : ModelRouteReason.POLICY_DEFAULT
            );
        }

        List<ModelCatalogEntry> runtimeCandidates =
                catalogRepository
                        .findEffectiveByRuntime(
                                runtime.provider(),
                                runtime.model(),
                                now
                        )
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        ModelCatalogEntry::modelKey
                                )
                        )
                        .toList();

        Optional<ModelCatalogEntry> executable =
                runtimeCandidates.stream()
                        .filter(entry ->
                                isAllowedByLists(
                                        entry.modelKey(),
                                        policy,
                                        policyEnabled
                                )
                        )
                        .filter(entry ->
                                entry.lifecycle() == ModelLifecycle.ACTIVE
                                        || entry.lifecycle()
                                        == ModelLifecycle.DEPRECATED
                        )
                        .findFirst();

        if (executable.isPresent()) {
            ModelCatalogEntry selected =
                    executable.get();

            return Selection.resolved(
                    selected,
                    selected.modelKey(),
                    ModelRouteReason.RUNTIME_ONLY_MATCH
            );
        }

        if (!runtimeCandidates.isEmpty()) {
            Optional<ModelCatalogEntry> policyVisible =
                    runtimeCandidates.stream()
                            .filter(entry ->
                                    isAllowedByLists(
                                            entry.modelKey(),
                                            policy,
                                            policyEnabled
                                    )
                            )
                            .findFirst();

            ModelCatalogEntry selected =
                    policyVisible.orElse(
                            runtimeCandidates.getFirst()
                    );

            return Selection.resolved(
                    selected,
                    selected.modelKey(),
                    ModelRouteReason.RUNTIME_ONLY_MATCH
            );
        }

        if (policyEnabled) {
            return Selection.modelNotFound(null);
        }

        if (catalogRepository.hasEffectiveHistoryByRuntime(
                runtime.provider(),
                runtime.model(),
                now
        )) {
            return new Selection(
                    null,
                    runtimeKey(runtime),
                    runtime.provider(),
                    runtime.model(),
                    null,
                    ModelRouteReason.RUNTIME_MISMATCH
            );
        }

        return new Selection(
                null,
                runtimeKey(runtime),
                runtime.provider(),
                runtime.model(),
                ModelRouteReason.LEGACY_RUNTIME_FALLBACK,
                null
        );
    }

    ModelRouteReason validateCatalogAndPolicy(
            ModelCatalogEntry entry,
            String modelKey,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            Set<ModelCapability> requiredCapabilities
    ) {
        if (entry.lifecycle() == ModelLifecycle.DISABLED
                || entry.lifecycle() == ModelLifecycle.RETIRED) {
            return ModelRouteReason.MODEL_DISABLED;
        }

        if (!entry.provider().equals(runtime.provider())
                || !entry.providerModelId().equals(runtime.model())) {
            return ModelRouteReason.RUNTIME_MISMATCH;
        }

        if (!isAllowedByLists(
                modelKey,
                policy,
                policyEnabled
        )) {
            if (policy != null
                    && policy.denyModelKeys().contains(modelKey)) {
                return ModelRouteReason.MODEL_DENIED;
            }

            return ModelRouteReason.MODEL_NOT_ALLOWED;
        }

        /*
         * End-to-end feature gate precedes catalog/runtime declarations. A
         * future runtime flag cannot accidentally activate TOOLS/VISION/etc.
         * before request representation + accounting + provider serialization
         * exist.
         */
        if (!ModelRoutingExecutionCapabilityGate
                .supportsAll(requiredCapabilities)) {
            return ModelRouteReason.CAPABILITY_UNSUPPORTED;
        }

        if (!entry.capabilities().containsAll(requiredCapabilities)
                || runtimeMissesCapability(
                runtime,
                requiredCapabilities
        )) {
            return ModelRouteReason.CAPABILITY_UNSUPPORTED;
        }

        if (policyEnabled && policy.requireNoTraining()) {
            if (entry.trainingUseStatus()
                    != ModelTrainingUseStatus.NOT_USED
                    && entry.trainingUseStatus()
                    != ModelTrainingUseStatus.CONTRACTUAL_NO_TRAINING) {
                return ModelRouteReason.TRAINING_POLICY_UNSATISFIED;
            }
        }

        if (policyEnabled
                && policy.requireZeroDataRetention()
                && entry.retentionStatus()
                != ModelRetentionStatus.ZERO_DATA_RETENTION) {
            return ModelRouteReason.RETENTION_POLICY_UNSATISFIED;
        }

        return null;
    }

    private static boolean isAllowedByLists(
            String modelKey,
            OrganizationModelPolicy policy,
            boolean policyEnabled
    ) {
        if (!policyEnabled || modelKey == null) {
            return true;
        }

        if (policy.denyModelKeys().contains(modelKey)) {
            return false;
        }

        return policy.allowModelKeys().isEmpty()
                || policy.allowModelKeys().contains(modelKey);
    }

    private static boolean runtimeMissesCapability(
            RuntimeModelStatusResponse runtime,
            Set<ModelCapability> capabilities
    ) {
        for (ModelCapability capability : capabilities) {
            boolean supported = switch (capability) {
                case TOOLS -> runtime.toolsSupported();
                case VISION -> runtime.visionSupported();
                case STRUCTURED_OUTPUT ->
                        runtime.structuredOutputSupported();
            };

            if (!supported) {
                return true;
            }
        }

        return false;
    }

    static String normalizeNullableKey(
            String value
    ) {
        return value == null || value.isBlank()
                ? null
                : ModelCatalogService.normalizeModelKey(value);
    }

    private static String runtimeKey(
            RuntimeModelStatusResponse runtime
    ) {
        return "runtime:"
                + runtime.provider()
                + ":"
                + runtime.model();
    }

    record Selection(
            ModelCatalogEntry entry,
            String modelKey,
            String selectedProvider,
            String selectedProviderModelId,
            ModelRouteReason allowedReason,
            ModelRouteReason denialReason
    ) {
        static Selection resolved(
                ModelCatalogEntry entry,
                String modelKey,
                ModelRouteReason allowedReason
        ) {
            return new Selection(
                    Objects.requireNonNull(entry),
                    modelKey,
                    entry.provider(),
                    entry.providerModelId(),
                    allowedReason,
                    null
            );
        }

        static Selection modelNotFound(
                String modelKey
        ) {
            return new Selection(
                    null,
                    modelKey,
                    null,
                    null,
                    null,
                    ModelRouteReason.MODEL_NOT_FOUND
            );
        }
    }
}
