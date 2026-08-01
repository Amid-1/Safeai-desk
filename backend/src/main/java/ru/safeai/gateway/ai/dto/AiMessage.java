package ru.safeai.gateway.ai.dto;

import java.util.Objects;

public record AiMessage(
        AiMessageRole role,
        String content
) {
    public static final int ABSOLUTE_MAX_CONTENT_CHARS = 100_000;

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

        if (content.length() > ABSOLUTE_MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "AI message content превышает абсолютный лимит "
                            + ABSOLUTE_MAX_CONTENT_CHARS
                            + " символов"
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
