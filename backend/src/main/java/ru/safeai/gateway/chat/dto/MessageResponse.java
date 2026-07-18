package ru.safeai.gateway.chat.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String role,
        String content,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        String usageStatus,
        BigDecimal costUsd,
        String pricingStatus,
        String currency,
        String pricingVersion,
        Instant pricingCalculatedAt,
        Instant createdAt,
        String status
) {
}
