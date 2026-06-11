package ru.safeai.gateway.chat.dto;

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
}