package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * Governed physical execution request.
 *
 * <p>A Knowledge route carries a server-created, durable permit. The permit
 * contains only source identities and hashes; each physical retry obtains its
 * own short-lived attempt lease immediately before the adapter reports STARTED.</p>
 */
public record AiExecutionRequest(
        UUID chatTurnId,
        UUID modelRouteDecisionId,
        AiChatRequest aiRequest,
        ProviderExecutionTarget target,
        AiExecutionIdentity executionIdentity,
        AiChatRequest reservedAiRequest,
        UUID knowledgeExecutionPermitId
) {
    public AiExecutionRequest(
            UUID chatTurnId,
            UUID modelRouteDecisionId,
            AiChatRequest aiRequest,
            ProviderExecutionTarget target,
            AiExecutionIdentity executionIdentity,
            AiChatRequest reservedAiRequest
    ) {
        this(chatTurnId, modelRouteDecisionId, aiRequest, target, executionIdentity,
                reservedAiRequest, null);
    }

    public AiExecutionRequest {
        Objects.requireNonNull(chatTurnId, "chatTurnId не должен быть null");
        Objects.requireNonNull(modelRouteDecisionId, "modelRouteDecisionId не должен быть null");
        Objects.requireNonNull(aiRequest, "aiRequest не должен быть null");
        Objects.requireNonNull(target, "target не должен быть null");
        Objects.requireNonNull(executionIdentity, "executionIdentity не должен быть null");
        Objects.requireNonNull(reservedAiRequest, "reservedAiRequest не должен быть null");

        if (!modelRouteDecisionId.equals(executionIdentity.decisionId())
                || !chatTurnId.equals(executionIdentity.chatTurnId())) {
            throw new IllegalStateException("Execution request has a different route identity");
        }
        if (executionIdentity.usesKnowledge() != (knowledgeExecutionPermitId != null)) {
            throw new IllegalStateException(
                    "Knowledge execution must carry exactly one durable execution permit");
        }
    }
}
