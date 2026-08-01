package ru.safeai.gateway.ai.provider;

import java.util.Objects;
import java.util.UUID;

public record AiProviderAttemptContext(
        UUID operationId,
        UUID attemptId,
        int attemptNumber,
        int maxAttempts
) {
    public AiProviderAttemptContext {
        Objects.requireNonNull(
                operationId,
                "operationId не должен быть null"
        );
        Objects.requireNonNull(
                attemptId,
                "attemptId не должен быть null"
        );

        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber должен быть положительным"
            );
        }
        if (maxAttempts < attemptNumber) {
            throw new IllegalArgumentException(
                    "maxAttempts не может быть меньше attemptNumber"
            );
        }
    }
}
