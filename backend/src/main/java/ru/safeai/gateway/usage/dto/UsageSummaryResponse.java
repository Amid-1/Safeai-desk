package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record UsageSummaryResponse(
        UUID userId,
        String userEmail,
        String model,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal costUsd
) {
    public UsageSummaryResponse(
            UUID userId,
            String userEmail,
            String model,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(
                userId,
                userEmail,
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