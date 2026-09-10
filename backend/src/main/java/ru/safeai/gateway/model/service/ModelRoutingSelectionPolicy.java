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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, fail-closed model selection rules shared by routing and policy preview.
 *
 * <p>No bootstrap path may execute a physical runtime that has no effective catalog
 * snapshot. Historical {@code LEGACY_RUNTIME_FALLBACK} evidence remains readable, but
 * this policy never creates new fallback decisions.</p>
 */
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
                        );

        List<ModelCatalogEntry> policyVisible =
                runtimeCandidates.stream()
                        .filter(entry ->
                                isAllowedByLists(
                                        entry.modelKey(),
                                        policy,
                                        policyEnabled
                                )
                        )
                        .toList();

        List<ModelCatalogEntry> executable =
                policyVisible.stream()
                        .filter(ModelRoutingSelectionPolicy::isRouteEligibleLifecycle)
                        .toList();

        if (executable.size() > 1) {
            return Selection.ambiguous(runtime);
        }

        if (executable.size() == 1) {
            ModelCatalogEntry selected = executable.getFirst();
            return Selection.resolved(
                    selected,
                    selected.modelKey(),
                    ModelRouteReason.RUNTIME_ONLY_MATCH
            );
        }

        /*
         * Multiple logical keys are ambiguous only when more than one candidate is
         * actually executable. If policy/lifecycle rules eliminate every candidate,
         * report the governing denial instead of an unrelated alphabetical choice.
         */
        if (!policyVisible.isEmpty()) {
            return Selection.denied(
                    runtime,
                    ModelRouteReason.MODEL_DISABLED,
                    policyVisible.size() == 1
                            ? policyVisible.getFirst().modelKey()
                            : null
            );
        }

        if (policyEnabled && !runtimeCandidates.isEmpty()) {
            return Selection.denied(
                    runtime,
                    ModelRouteReason.MODEL_NOT_ALLOWED,
                    runtimeCandidates.size() == 1
                            ? runtimeCandidates.getFirst().modelKey()
                            : null
            );
        }

        /*
         * Strict production invariant: no effective catalog snapshot means no
         * execution. A stale historical mapping is reported as runtime mismatch;
         * a never-governed runtime is model-not-found. Neither is executable.
         */
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

        return Selection.modelNotFound(null);
    }

    ModelRouteReason validateCatalogAndPolicy(
            ModelCatalogEntry entry,
            String modelKey,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            Set<ModelCapability> requiredCapabilities
    ) {
        if (!isRouteEligibleLifecycle(entry)) {
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

    private static boolean isRouteEligibleLifecycle(
            ModelCatalogEntry entry
    ) {
        return entry.lifecycle() == ModelLifecycle.ACTIVE
                || entry.lifecycle() == ModelLifecycle.DEPRECATED;
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

        static Selection denied(
                RuntimeModelStatusResponse runtime,
                ModelRouteReason denialReason,
                String modelKey
        ) {
            return new Selection(
                    null,
                    modelKey,
                    runtime.provider(),
                    runtime.model(),
                    null,
                    Objects.requireNonNull(denialReason)
            );
        }

        static Selection ambiguous(
                RuntimeModelStatusResponse runtime
        ) {
            return new Selection(
                    null,
                    null,
                    runtime.provider(),
                    runtime.model(),
                    null,
                    ModelRouteReason.AMBIGUOUS_RUNTIME_MAPPING
            );
        }
    }
}
