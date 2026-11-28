package ru.safeai.gateway.chat.repository;

import ru.safeai.gateway.chat.entity.ChatTurnState;

import java.util.UUID;

public record RecoveredChatTurn(
        UUID turnId,
        UUID organizationId,
        UUID sessionId,
        UUID clientRequestId,
        ChatTurnState state,
        String failureCode,
        boolean outcomeAmbiguous,
        UUID correlationId
) {
    public RecoveredChatTurn(UUID turnId, UUID organizationId, UUID sessionId,
                             UUID clientRequestId, ChatTurnState state, String failureCode,
                             boolean outcomeAmbiguous) {
        this(turnId, organizationId, sessionId, clientRequestId, state, failureCode,
                outcomeAmbiguous, null);
    }
}
