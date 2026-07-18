package ru.safeai.gateway.ai.dto;

import java.util.Objects;

public record AiMessage(
        AiMessageRole role,
        String content
) {

    public AiMessage {
        Objects.requireNonNull(
                role,
                "role не должен быть null"
        );

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "AI message content не должен быть пустым"
            );
        }
    }

    public AiMessage(
            String role,
            String content
    ) {
        this(
                AiMessageRole.parse(role),
                content
        );
    }
}