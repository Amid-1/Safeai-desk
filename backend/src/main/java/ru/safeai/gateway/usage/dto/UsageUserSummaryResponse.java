package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** userEmail — текущее display-значение, а userId — стабильная identity. */
public record UsageUserSummaryResponse(
        UUID userId,
        String userEmail,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal costUsd
) {
    public UsageUserSummaryResponse {
        userId = Objects.requireNonNull(userId, "userId не должен быть null");
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("userEmail не должен быть пустым");
        }
        userEmail = userEmail.trim();
        inputTokens = nonNegative(inputTokens, "inputTokens");
        outputTokens = nonNegative(outputTokens, "outputTokens");
        totalTokens = Math.addExact(inputTokens, outputTokens);
        costUsd = normalizeCost(costUsd);
    }

    public UsageUserSummaryResponse(
            UUID userId,
            String userEmail,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(userId, userEmail, safeLong(inputTokens), safeLong(outputTokens), 0L, costUsd);
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
