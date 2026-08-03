package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChatProcessingContext(
        UUID chatId,
        UUID turnId,
        UUID userMessageId,
        UUID clientRequestId,
        UUID providerOperationId,
        UUID processingToken,
        Instant leaseUntil,
        AiChatRequest aiRequest,
        boolean replay
) {
    public ChatProcessingContext {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(turnId, "turnId не должен быть null");
        Objects.requireNonNull(
                userMessageId,
                "userMessageId не должен быть null"
        );
        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );
        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );
        if (replay) {
            if (processingToken != null || leaseUntil != null || aiRequest != null) {
                throw new IllegalArgumentException(
                        "Replay context не может содержать processing metadata"
                );
            }
        } else {
            Objects.requireNonNull(
                    processingToken,
                    "processingToken не должен быть null"
            );
            Objects.requireNonNull(leaseUntil, "leaseUntil не должен быть null");
            Objects.requireNonNull(aiRequest, "aiRequest не должен быть null");
            if (!providerOperationId.equals(aiRequest.providerOperationId())) {
                throw new IllegalArgumentException(
                        "providerOperationId context и AI request не совпадают"
                );
            }
        }
    }
}
