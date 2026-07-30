package ru.safeai.gateway.audit.details;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AiResponseAuditDetails(
        UUID chatId,
        UUID messageId,
        UUID userMessageId,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd,
        Long durationMs,
        String providerMessageId,
        String finishReason,
        String responseStatus,
        String usageStatus,
        String pricingStatus,
        String currency,
        String pricingVersion,
        Instant pricingCalculatedAt
) implements AuditDetails {

    public AiResponseAuditDetails {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );
        Objects.requireNonNull(
                messageId,
                "messageId не должен быть null"
        );
        Objects.requireNonNull(
                userMessageId,
                "userMessageId не должен быть null"
        );

        model = normalize(model);
        providerMessageId = normalize(providerMessageId);
        finishReason = normalize(finishReason);
        responseStatus = normalize(responseStatus);
        usageStatus = normalize(usageStatus);
        pricingStatus = normalize(pricingStatus);
        currency = normalize(currency);
        pricingVersion = normalize(pricingVersion);

        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(durationMs, "durationMs");

        if (costUsd != null && costUsd.signum() < 0) {
            throw new IllegalArgumentException(
                    "costUsd не может быть отрицательным"
            );
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();

        put(result, "chatId", chatId);
        put(result, "messageId", messageId);
        put(result, "userMessageId", userMessageId);
        put(result, "model", model);
        put(result, "inputTokens", inputTokens);
        put(result, "outputTokens", outputTokens);
        put(result, "costUsd", costUsd);
        put(result, "durationMs", durationMs);
        put(
                result,
                "providerMessageId",
                providerMessageId
        );
        put(result, "finishReason", finishReason);
        put(
                result,
                "aiResponseStatus",
                responseStatus
        );
        put(result, "usageStatus", usageStatus);
        put(result, "pricingStatus", pricingStatus);
        put(result, "currency", currency);
        put(result, "pricingVersion", pricingVersion);
        put(
                result,
                "pricingCalculatedAt",
                pricingCalculatedAt
        );

        return Map.copyOf(result);
    }

    private static void requireNonNegative(
            Number value,
            String field
    ) {
        if (value != null && value.longValue() < 0) {
            throw new IllegalArgumentException(
                    field + " не может быть отрицательным"
            );
        }
    }

    private static void put(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
