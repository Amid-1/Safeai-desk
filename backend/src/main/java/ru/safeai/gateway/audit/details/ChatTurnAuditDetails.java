package ru.safeai.gateway.audit.details;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ChatTurnAuditDetails(
        UUID chatId,
        UUID turnId,
        UUID userMessageId,
        UUID assistantMessageId,
        UUID clientRequestId,
        UUID providerOperationId,
        String turnState,
        String provider,
        String requestedModel,
        String resolvedModel,
        String providerRequestId,
        String providerErrorType,
        String failureCode,
        Boolean outcomeAmbiguous,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd,
        String usageStatus,
        String pricingStatus,
        String currency
) implements AuditDetails {

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "chatId", chatId);
        put(result, "turnId", turnId);
        put(result, "userMessageId", userMessageId);
        put(result, "assistantMessageId", assistantMessageId);
        put(result, "clientRequestId", clientRequestId);
        put(result, "providerOperationId", providerOperationId);
        put(result, "turnState", turnState);
        put(result, "provider", provider);
        put(result, "requestedModel", requestedModel);
        put(result, "resolvedModel", resolvedModel);
        put(result, "providerRequestId", providerRequestId);
        put(result, "providerErrorType", providerErrorType);
        put(result, "failureCode", failureCode);
        put(result, "outcomeAmbiguous", outcomeAmbiguous);
        put(result, "inputTokens", inputTokens);
        put(result, "outputTokens", outputTokens);
        put(result, "costUsd", costUsd);
        put(result, "usageStatus", usageStatus);
        put(result, "pricingStatus", pricingStatus);
        put(result, "currency", currency);
        return Map.copyOf(result);
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
}
