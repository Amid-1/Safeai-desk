package ru.safeai.gateway.ai.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AiChatRequest(
        UUID userId,
        UUID organizationId,
        UUID chatId,
        String userMessage,
        List<AiMessage> history
) {

    public AiChatRequest {
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(userMessage, "userMessage не должен быть null");

        if (userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage не должен быть пустым");
        }

        history = history == null
                ? List.of()
                : history.stream()
                .filter(Objects::nonNull)
                .toList();
    }
}