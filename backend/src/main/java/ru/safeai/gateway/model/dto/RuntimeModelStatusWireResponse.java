package ru.safeai.gateway.model.dto;

import java.math.BigDecimal;

/** Public runtime DTO. Money is serialized as exact decimal strings. */
public record RuntimeModelStatusWireResponse(
        String provider,
        String model,
        boolean enabled,
        String routingMode,
        int maxInputTokens,
        int maxOutputTokens,
        boolean toolsSupported,
        boolean visionSupported,
        boolean structuredOutputSupported,
        String dataRetentionStatus,
        String healthStatus,
        String pricingStatus,
        String inputUsdPer1mTokens,
        String outputUsdPer1mTokens,
        String pricingVersion
) {
    public static RuntimeModelStatusWireResponse from(
            RuntimeModelStatusResponse value
    ) {
        return new RuntimeModelStatusWireResponse(
                value.provider(),
                value.model(),
                value.enabled(),
                value.routingMode(),
                value.maxInputTokens(),
                value.maxOutputTokens(),
                value.toolsSupported(),
                value.visionSupported(),
                value.structuredOutputSupported(),
                value.dataRetentionStatus(),
                value.healthStatus(),
                value.pricingStatus(),
                decimal(value.inputUsdPer1mTokens()),
                decimal(value.outputUsdPer1mTokens()),
                value.pricingVersion()
        );
    }

    private static String decimal(
            BigDecimal value
    ) {
        return value == null
                ? null
                : value.toPlainString();
    }
}
