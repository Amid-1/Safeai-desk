package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.service.ModelRouteExecutionIdentity;

import java.util.Objects;
import java.util.UUID;

/**
 * Governed physical execution request.
 *
 * <p>A provider call is never authorized by the prepared request alone:
 * it must carry the DB-backed execution identity and the exact sealed
 * pre-RAG request used to issue that identity.</p>
 */
public record AiExecutionRequest(
        UUID chatTurnId,
        UUID modelRouteDecisionId,
        AiChatRequest aiRequest,
        ProviderExecutionTarget target,
        ModelRouteExecutionIdentity executionIdentity,
        AiChatRequest reservedAiRequest
) {

    public AiExecutionRequest {
        Objects.requireNonNull(
                chatTurnId,
                "chatTurnId не должен быть null"
        );
        Objects.requireNonNull(
                modelRouteDecisionId,
                "modelRouteDecisionId не должен быть null"
        );
        Objects.requireNonNull(
                aiRequest,
                "aiRequest не должен быть null"
        );
        Objects.requireNonNull(
                target,
                "target не должен быть null"
        );
        Objects.requireNonNull(
                executionIdentity,
                "executionIdentity не должен быть null"
        );
        Objects.requireNonNull(
                reservedAiRequest,
                "reservedAiRequest не должен быть null"
        );

        if (!modelRouteDecisionId.equals(executionIdentity.decisionId())
                || !chatTurnId.equals(executionIdentity.chatTurnId())) {
            throw new IllegalStateException(
                    "Execution request has a different route identity"
            );
        }
    }
}
