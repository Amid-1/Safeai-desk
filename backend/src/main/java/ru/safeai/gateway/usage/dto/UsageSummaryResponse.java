package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * userId — стабильная identity пользователя.
 * userEmail — текущее display-значение, а не исторический snapshot.
 */
public record UsageSummaryResponse(
        UUID userId,
        String userEmail,
        String model,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal costUsd
) {
    public UsageSummaryResponse {
        userId = Objects.requireNonNull(userId, "userId не должен быть null");
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("userEmail не должен быть пустым");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model не должен быть пустым");
        }
        userEmail = userEmail.trim();
        model = model.trim();
        inputTokens = nonNegative(inputTokens, "inputTokens");
        outputTokens = nonNegative(outputTokens, "outputTokens");
        totalTokens = Math.addExact(inputTokens, outputTokens);
        costUsd = normalizeCost(costUsd);
    }

    public UsageSummaryResponse(
            UUID userId,
            String userEmail,
            String model,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(userId, userEmail, model, safeLong(inputTokens), safeLong(outputTokens), 0L, costUsd);
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " не может быть отрицательным");
        }
        return value;
    }

    private static BigDecimal normalizeCost(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("costUsd не может быть отрицательным");
        }
        return normalized;
    }
}
