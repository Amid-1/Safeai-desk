package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;

public record UsageModelSummaryResponse(
        String model,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal costUsd
) {
    public UsageModelSummaryResponse {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model не должен быть пустым");
        }
        model = model.trim();
        inputTokens = nonNegative(inputTokens, "inputTokens");
        outputTokens = nonNegative(outputTokens, "outputTokens");
        totalTokens = Math.addExact(inputTokens, outputTokens);
        costUsd = normalizeCost(costUsd);
    }

    public UsageModelSummaryResponse(
            String model,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(model, safeLong(inputTokens), safeLong(outputTokens), 0L, costUsd);
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
