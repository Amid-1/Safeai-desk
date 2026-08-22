package ru.safeai.gateway.chat.dto;

import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SendMessageResponse(
        UUID chatId,
        UUID turnId,
        UUID clientRequestId,
        UUID providerOperationId,
        String state,
        boolean replay,
        MessageResponse userMessage,
        MessageResponse assistantMessage,
        Instant chatUpdatedAt,
        Instant createdAt,
        Instant completedAt,
        AnswerPassportResponse answerPassport
) {
    public SendMessageResponse {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(turnId, "turnId не должен быть null");
        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );
        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state не должен быть пустым");
        }
        Objects.requireNonNull(
                userMessage,
                "userMessage не должен быть null"
        );
        Objects.requireNonNull(
                assistantMessage,
                "assistantMessage не должен быть null для SUCCEEDED turn"
        );
        Objects.requireNonNull(createdAt, "createdAt не должен быть null");
        Objects.requireNonNull(completedAt, "completedAt не должен быть null");
    }

    public SendMessageResponse(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            UUID providerOperationId,
            String state,
            boolean replay,
            MessageResponse userMessage,
            MessageResponse assistantMessage,
            Instant chatUpdatedAt,
            Instant createdAt,
            Instant completedAt
    ) {
        this(
                chatId,
                turnId,
                clientRequestId,
                providerOperationId,
                state,
                replay,
                userMessage,
                assistantMessage,
                chatUpdatedAt,
                createdAt,
                completedAt,
                null
        );
    }
}
