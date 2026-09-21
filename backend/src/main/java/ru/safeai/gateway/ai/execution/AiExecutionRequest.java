package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.service.ModelRouteExecutionIdentity;

import java.util.Objects;
import java.util.UUID;

/** Governed physical execution; never accept an identity-less direct call. */
public record AiExecutionRequest(
        UUID chatTurnId,
        UUID modelRouteDecisionId,
        AiChatRequest aiRequest,
        ProviderExecutionTarget target,
        ModelRouteExecutionIdentity executionIdentity,
        AiChatRequest reservedAiRequest
) {
    public AiExecutionRequest {
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(modelRouteDecisionId, "modelRouteDecisionId");
        Objects.requireNonNull(aiRequest, "aiRequest");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(executionIdentity, "executionIdentity");
        Objects.requireNonNull(reservedAiRequest, "reservedAiRequest");
        if (!modelRouteDecisionId.equals(executionIdentity.decisionId())
                || !chatTurnId.equals(executionIdentity.chatTurnId())) {
            throw new IllegalStateException("Execution request has a different route identity");
        }
    }
}
