package ru.safeai.gateway.chat.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UsageDailySummaryResponse(
        LocalDate usageDate,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal costUsd
) {
    public UsageDailySummaryResponse(
            LocalDate usageDate,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd
    ) {
        this(
                usageDate,
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