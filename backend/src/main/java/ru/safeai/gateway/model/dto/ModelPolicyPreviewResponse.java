package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Side-effect-free provider preview. It evaluates persisted catalog/runtime data
 * against an unsaved draft policy and never calls an external model provider.
 */
public record ModelPolicyPreviewResponse(
        UUID organizationId,
        int basePolicyVersion,
        boolean enabled,
        Instant evaluatedAt,
        String runtimeProvider,
        String runtimeModel,
        ModelRouteOutcome automaticOutcome,
        ModelRouteReason automaticReason,
        String automaticModelKey,
        int executableModelCount,
        boolean wouldLockOutOrganization,
        List<ModelPolicyPreviewItemResponse> models,
        boolean nonBinding
) {
    /**
     * Older callers keep their signature. A preview is always indicative:
     * actual routing re-evaluates under the transactional budget lock.
     */
    public ModelPolicyPreviewResponse(
            UUID organizationId, int basePolicyVersion, boolean enabled,
            Instant evaluatedAt, String runtimeProvider, String runtimeModel,
            ModelRouteOutcome automaticOutcome, ModelRouteReason automaticReason,
            String automaticModelKey, int executableModelCount,
            boolean wouldLockOutOrganization,
            List<ModelPolicyPreviewItemResponse> models
    ) {
        this(organizationId, basePolicyVersion, enabled, evaluatedAt,
                runtimeProvider, runtimeModel, automaticOutcome,
                automaticReason, automaticModelKey, executableModelCount,
                wouldLockOutOrganization, models, true);
    }

    public ModelPolicyPreviewResponse {
        if (!nonBinding) {
            throw new IllegalArgumentException(
                    "Budget preview cannot promise the future routing outcome"
            );
        }
        models = List.copyOf(models);
    }
}
