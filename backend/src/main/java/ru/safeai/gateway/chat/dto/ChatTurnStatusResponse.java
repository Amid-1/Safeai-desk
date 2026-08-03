package ru.safeai.gateway.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatTurnStatusResponse(
        UUID chatId,
        UUID turnId,
        UUID clientRequestId,
        UUID providerOperationId,
        String state,
        String provider,
        String requestedModel,
        String resolvedModel,
        String providerRequestId,
        String providerErrorType,
        String failureCode,
        boolean outcomeAmbiguous,
        Instant leaseUntil,
        Instant providerCallStartedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        MessageResponse userMessage,
        MessageResponse assistantMessage
) {
}
