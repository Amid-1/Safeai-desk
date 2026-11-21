package ru.safeai.gateway.model.pricing;

import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelPricingStatus;

import java.math.BigDecimal;

/** Shared fail-closed price-book contract for reservation, display and settlement. */
public final class CatalogPricingEligibility {
    private CatalogPricingEligibility() {}

    public static boolean verified(ModelCatalogEntry entry) {
        if (entry == null || !entry.pricingComplete()
                || entry.pricingScheme() != ModelPricingScheme.FLAT_TOKEN_V1
                || !presentNonNegative(entry.inputUsdPer1mTokens())
                || !presentNonNegative(entry.outputUsdPer1mTokens())
                || !optionalNonNegative(entry.cachedInputUsdPer1mTokens())
                || !optionalNonNegative(entry.cacheWriteInputUsdPer1mTokens())
                || !"{}".equals(entry.extraPricingJson())) {
            return false;
        }
        if (entry.pricingStatus() == ModelPricingStatus.FREE) {
            return zero(entry.inputUsdPer1mTokens()) && zero(entry.outputUsdPer1mTokens())
                    && zero(entry.cachedInputUsdPer1mTokens())
                    && zero(entry.cacheWriteInputUsdPer1mTokens());
        }
        return entry.pricingStatus() == ModelPricingStatus.CONFIGURED
                && entry.pricingVersion() != null && !entry.pricingVersion().isBlank()
                && !(zero(entry.inputUsdPer1mTokens()) && zero(entry.outputUsdPer1mTokens())
                    && zero(entry.cachedInputUsdPer1mTokens())
                    && zero(entry.cacheWriteInputUsdPer1mTokens()));
    }

    private static boolean presentNonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    private static boolean optionalNonNegative(BigDecimal value) {
        return value == null || presentNonNegative(value);
    }

    private static boolean zero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }
}
