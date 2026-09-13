package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import java.util.Objects;
import java.util.UUID;

public record AiExecutionRequest(UUID chatTurnId, UUID modelRouteDecisionId,
                                 AiChatRequest aiRequest, ProviderExecutionTarget target) {
    public AiExecutionRequest {
        Objects.requireNonNull(chatTurnId, "chatTurnId не должен быть null");
        Objects.requireNonNull(aiRequest, "aiRequest не должен быть null");
        Objects.requireNonNull(target, "target не должен быть null");
    }
}
