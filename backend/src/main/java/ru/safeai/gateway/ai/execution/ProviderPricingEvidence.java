package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.PricingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProviderPricingEvidence(
        PricingStatus pricingStatus,
        BigDecimal costUsd,
        String currency,
        String priceBookVersion,
        Instant calculatedAt
) {
    public static ProviderPricingEvidence from(AiChatResponse response) {
        return new ProviderPricingEvidence(
                response.pricingStatus(), response.costUsd(), response.currency(),
                response.priceVersion(), response.pricingCalculatedAt()
        );
    }
}
