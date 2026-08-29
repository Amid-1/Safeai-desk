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
        String requested = normalizeNullableKey(
                request.requestedModelKey()
        );
        String policyDefault = policyEnabled
                ? policy.defaultModelKey()
                : null;
        String explicit = requested != null
                ? requested
                : policyDefault;

        if (explicit != null) {
            ModelCatalogEntry entry = catalogRepository
                    .findEffective(explicit, now)
                    .orElse(null);
            return entry == null
                    ? new Selection(
                    null,
                    explicit,
                    null,
                    ModelRouteReason.MODEL_NOT_FOUND
            )
                    : new Selection(
                    entry,
                    explicit,
                    ModelRouteReason.POLICY_DEFAULT,
                    null
            );
        }

        List<ModelCatalogEntry> runtimeCandidates = catalogRepository
                .findEffectiveByRuntime(
                        runtime.provider(),
                        runtime.model(),
                        now
                )
                .stream()
                .sorted(Comparator.comparing(
                        ModelCatalogEntry::modelKey
                ))
                .toList();

        Optional<ModelCatalogEntry> executable = runtimeCandidates
                .stream()
                .filter(entry -> isAllowedByLists(
                        entry.modelKey(),
                        policy,
                        policyEnabled
                ))
                .filter(entry ->
                        entry.lifecycle() == ModelLifecycle.ACTIVE
                                || entry.lifecycle()
                                == ModelLifecycle.DEPRECATED
                )
                .findFirst();

        if (executable.isPresent()) {
            ModelCatalogEntry selected = executable.get();
            return new Selection(
                    selected,
                    selected.modelKey(),
                    ModelRouteReason.RUNTIME_ONLY_MATCH,
                    null
            );
        }

        /*
         * A current runtime candidate exists, but lifecycle or tenant policy
         * makes it non-executable. Keep the exact snapshot so validation emits
         * MODEL_DISABLED / MODEL_DENIED / MODEL_NOT_ALLOWED instead of silently
         * falling back to legacy execution.
         */
        if (!runtimeCandidates.isEmpty()) {
            Optional<ModelCatalogEntry> policyVisible = runtimeCandidates
                    .stream()
                    .filter(entry -> isAllowedByLists(
                            entry.modelKey(),
                            policy,
                            policyEnabled
                    ))
                    .findFirst();
            ModelCatalogEntry selected = policyVisible
                    .orElse(runtimeCandidates.getFirst());
            return new Selection(
                    selected,
                    selected.modelKey(),
                    ModelRouteReason.RUNTIME_ONLY_MATCH,
                    null
            );
        }

        if (policyEnabled) {
            return new Selection(
                    null,
                    null,
                    null,
                    ModelRouteReason.MODEL_NOT_FOUND
            );
        }

        /*
         * Bootstrap fallback is valid only until this physical runtime identity
         * has had an already-effective catalog version. Future scheduled rows
         * intentionally do not disable bootstrap before activation.
         */
        if (catalogRepository.hasEffectiveHistoryByRuntime(
                runtime.provider(),
                runtime.model(),
                now
        )) {
            return new Selection(
                    null,
                    runtimeKey(runtime),
                    null,
                    ModelRouteReason.RUNTIME_MISMATCH
            );
        }

        return new Selection(
                null,
                runtimeKey(runtime),
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

        if (!isAllowedByLists(modelKey, policy, policyEnabled)) {
            if (policy != null
                    && policy.denyModelKeys().contains(modelKey)) {
                return ModelRouteReason.MODEL_DENIED;
            }
            return ModelRouteReason.MODEL_NOT_ALLOWED;
        }

        if (!entry.capabilities().containsAll(requiredCapabilities)
                || runtimeMissesCapability(runtime, requiredCapabilities)) {
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
        return "runtime:" + runtime.provider() + ":" + runtime.model();
    }

    record Selection(
            ModelCatalogEntry entry,
            String modelKey,
            ModelRouteReason allowedReason,
            ModelRouteReason denialReason
    ) {
    }
}
