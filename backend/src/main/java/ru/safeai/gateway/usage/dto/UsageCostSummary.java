package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.util.Objects;

public record UsageCostSummary(
        BigDecimal knownCostUsd,
        String currency,
        long pricedMessages,
        long freeMessages,
        long unpricedMessages,
        long pricingFailedMessages,
        long pricingNotApplicableMessages,
        boolean pricingComplete
) {

    private static final String SUPPORTED_CURRENCY = "USD";

    public UsageCostSummary {
        knownCostUsd = normalizeCost(knownCostUsd);
        currency = requireUsd(currency);

        validateNonNegative(
                pricedMessages,
                "pricedMessages"
        );
        validateNonNegative(
                freeMessages,
                "freeMessages"
        );
        validateNonNegative(
                unpricedMessages,
                "unpricedMessages"
        );
        validateNonNegative(
                pricingFailedMessages,
                "pricingFailedMessages"
        );
        validateNonNegative(
                pricingNotApplicableMessages,
                "pricingNotApplicableMessages"
        );

        pricingComplete = unpricedMessages == 0
                && pricingFailedMessages == 0;
    }

    public UsageCostSummary(
            BigDecimal knownCostUsd,
            long pricedMessages,
            long freeMessages,
            long unpricedMessages,
            long pricingFailedMessages,
            long pricingNotApplicableMessages
    ) {
        this(
                knownCostUsd,
                SUPPORTED_CURRENCY,
                pricedMessages,
                freeMessages,
                unpricedMessages,
                pricingFailedMessages,
                pricingNotApplicableMessages,
                false
        );
    }

    public long classifiedMessages() {
        long knownPricingMessages = Math.addExact(
                pricedMessages,
                freeMessages
        );

        long incompletePricingMessages = Math.addExact(
                unpricedMessages,
                pricingFailedMessages
        );

        return Math.addExact(
                Math.addExact(
                        knownPricingMessages,
                        incompletePricingMessages
                ),
                pricingNotApplicableMessages
        );
    }

    private static BigDecimal normalizeCost(
            BigDecimal value
    ) {
        BigDecimal normalized = Objects.requireNonNullElse(
                value,
                BigDecimal.ZERO
        );

        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(
                    "knownCostUsd не может быть отрицательным"
            );
        }

        return normalized;
    }

    private static String requireUsd(
            String value
    ) {
        if (!SUPPORTED_CURRENCY.equals(value)) {
            throw new IllegalArgumentException(
                    "В текущей версии usage поддерживается только USD"
            );
        }

        return SUPPORTED_CURRENCY;
    }

    private static void validateNonNegative(
            long value,
            String propertyName
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    propertyName
                            + " не может быть отрицательным"
            );
        }
    }
}