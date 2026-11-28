package ru.safeai.gateway.model.dto;

import java.math.BigDecimal;
import ru.safeai.gateway.ai.provider.ProviderDataEvidence;

/**
 * Internal read model for the one adapter that the current runtime can
 * physically execute. Financial values remain BigDecimal inside Java.
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
        String pricingVersion,
        ProviderDataEvidence.Status dataAssurance
) {
    public RuntimeModelStatusResponse(String provider, String model, boolean enabled,
            String routingMode, int maxInputTokens, int maxOutputTokens,
            boolean toolsSupported, boolean visionSupported, boolean structuredOutputSupported,
            String dataRetentionStatus, String healthStatus, String pricingStatus,
            BigDecimal inputUsdPer1mTokens, BigDecimal outputUsdPer1mTokens, String pricingVersion) {
        this(provider, model, enabled, routingMode, maxInputTokens, maxOutputTokens,
                toolsSupported, visionSupported, structuredOutputSupported, dataRetentionStatus,
                healthStatus, pricingStatus, inputUsdPer1mTokens, outputUsdPer1mTokens,
                pricingVersion, ProviderDataEvidence.Status.UNKNOWN);
    }

    public RuntimeModelStatusResponse {
        java.util.Objects.requireNonNull(dataAssurance);
    }
}
