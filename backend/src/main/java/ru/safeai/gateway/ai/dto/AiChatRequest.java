package ru.safeai.gateway.ai.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AiChatRequest(
        UUID userId,
        UUID organizationId,
        UUID chatId,
        UUID providerOperationId,
        String userMessage,
        List<AiMessage> history
) {

    public AiChatRequest {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );

        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "userMessage не должен быть пустым"
            );
        }

        history = history == null
                ? List.of()
                : List.copyOf(history);
    }

    public AiChatRequest(
            UUID userId,
            UUID organizationId,
            UUID chatId,
            String userMessage,
            List<AiMessage> history
    ) {
        this(
                userId,
                organizationId,
                chatId,
                UUID.randomUUID(),
                userMessage,
                history
        );
    }
}