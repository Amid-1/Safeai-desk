package ru.safeai.gateway.chat.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID clientRequestId,
        UUID replyToMessageId,
        String role,
        String status,
        String content,
        String requestedModel,
        String model,
        String providerMessageId,
        String providerRequestId,
        String aiResponseStatus,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens,
        String usageStatus,
        BigDecimal costUsd,
        String pricingStatus,
        String currency,
        String pricingVersion,
        Instant pricingCalculatedAt,
        Instant createdAt
) {
}
