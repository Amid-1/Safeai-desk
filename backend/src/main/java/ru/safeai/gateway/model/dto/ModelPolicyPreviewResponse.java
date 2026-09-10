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
        List<ModelPolicyPreviewItemResponse> models
) {
    public ModelPolicyPreviewResponse {
        models = List.copyOf(models);
    }
}
