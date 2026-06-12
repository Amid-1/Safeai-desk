package ru.safeai.gateway.chat.dto;

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
                costUsd
        );
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : value;
    }
}