package ru.safeai.gateway.model.dto;

import java.math.BigDecimal;

/**
 * Read-only truth about the one adapter that the current runtime can actually
 * route to. It deliberately does not imply a successful live provider probe.
 */
public record RuntimeModelStatusResponse(
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
        BigDecimal inputUsdPer1mTokens,
        BigDecimal outputUsdPer1mTokens,
        String pricingVersion
) {
}
