package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PricingResultTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T12:00:00Z");

    private static final BigDecimal ZERO_COST =
            new BigDecimal("0.000000000000");

    private static final BigDecimal POSITIVE_COST =
            new BigDecimal("1.000000000000");

    private static final String CURRENCY_USD =
            "USD";

    private static final String PRICE_VERSION =
            "v1";

    @Test
    void pricedRequiresStrictlyPositiveCost() {
        assertThatThrownBy(
                () -> new PricingResult(
                        PricingStatus.PRICED,
                        ZERO_COST,
                        CURRENCY_USD,
                        PRICE_VERSION,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRICED");
    }

    @Test
    void freeRequiresExactlyZeroCost() {
        assertThatThrownBy(
                () -> new PricingResult(
                        PricingStatus.FREE,
                        POSITIVE_COST,
                        CURRENCY_USD,
                        PRICE_VERSION,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREE");
    }

    @Test
    void nonUsdCannotBeStoredInCostUsd() {
        assertThatThrownBy(
                () -> new PricingResult(
                        PricingStatus.PRICED,
                        POSITIVE_COST,
                        "EUR",
                        PRICE_VERSION,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void unpricedCannotCarryPriceMetadata() {
        assertThatThrownBy(
                () -> new PricingResult(
                        PricingStatus.UNPRICED,
                        POSITIVE_COST,
                        CURRENCY_USD,
                        PRICE_VERSION,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNPRICED");
    }
}