package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;

public record UsageModelSummaryResponse(
        String model,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal costUsd
) {
    public UsageModelSummaryResponse(
            String model,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(
                model,
                inputTokens,
                outputTokens,
                safeLong(inputTokens) + safeLong(outputTokens),
                safeBigDecimal(costUsd)
        );
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal safeBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}