package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record UsageDailySummaryResponse(
        LocalDate usageDate,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal costUsd
) {
    public UsageDailySummaryResponse {
        usageDate = Objects.requireNonNull(usageDate, "usageDate не должен быть null");
        inputTokens = nonNegative(inputTokens, "inputTokens");
        outputTokens = nonNegative(outputTokens, "outputTokens");
        totalTokens = Math.addExact(inputTokens, outputTokens);
        costUsd = normalizeCost(costUsd);
    }

    public UsageDailySummaryResponse(
            LocalDate usageDate,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(usageDate, safeLong(inputTokens), safeLong(outputTokens), 0L, costUsd);
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
