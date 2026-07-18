package ru.safeai.gateway.ai.dto;

import java.util.Locale;

public enum AiMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    DEVELOPER;

    public static AiMessageRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "AI message role не должен быть пустым"
            );
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Недопустимая AI message role: " + value,
                    exception
            );
        }
    }

    public String providerValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
