package ru.safeai.gateway.ai.pricing;

import ru.safeai.gateway.ai.metadata.PricingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PricingResult(
        PricingStatus status,
        BigDecimal costUsd,
        String currency,
        String priceVersion,
        Instant calculatedAt
) {

    public PricingResult {
        Objects.requireNonNull(
                status,
                "status не должен быть null"
        );

        Objects.requireNonNull(
                calculatedAt,
                "calculatedAt не должен быть null"
        );

        if (costUsd != null && costUsd.signum() < 0) {
            throw new IllegalArgumentException(
                    "costUsd не может быть отрицательным"
            );
        }

        validateMetadata(
                status,
                costUsd,
                currency,
                priceVersion
        );
    }

    public static PricingResult unpriced(
            Instant calculatedAt
    ) {
        return new PricingResult(
                PricingStatus.UNPRICED,
                null,
                null,
                null,
                calculatedAt
        );
    }

    public static PricingResult calculationFailed(
            Instant calculatedAt
    ) {
        return new PricingResult(
                PricingStatus.CALCULATION_FAILED,
                null,
                null,
                null,
                calculatedAt
        );
    }

    private static void validateMetadata(
            PricingStatus status,
            BigDecimal costUsd,
            String currency,
            String priceVersion
    ) {
        switch (status) {
            case PRICED -> validatePriced(
                    costUsd,
                    currency,
                    priceVersion
            );

            case FREE -> validateFree(
                    costUsd,
                    currency,
                    priceVersion
            );

            case UNPRICED,
                 CALCULATION_FAILED,
                 NOT_APPLICABLE ->
                    validateAbsentPriceMetadata(
                            costUsd,
                            currency,
                            priceVersion,
                            status
                    );
        }
    }

    private static void validatePriced(
            BigDecimal costUsd,
            String currency,
            String priceVersion
    ) {
        if (costUsd == null || costUsd.signum() < 0) {
            throw new IllegalArgumentException(
                    "PRICED требует неотрицательный costUsd"
            );
        }

        requirePriceIdentity(
                currency,
                priceVersion
        );
    }

    private static void validateFree(
            BigDecimal costUsd,
            String currency,
            String priceVersion
    ) {
        if (costUsd == null || costUsd.signum() != 0) {
            throw new IllegalArgumentException(
                    "FREE требует costUsd = 0"
            );
        }

        requirePriceIdentity(
                currency,
                priceVersion
        );
    }

    private static void validateAbsentPriceMetadata(
            BigDecimal costUsd,
            String currency,
            String priceVersion,
            PricingStatus status
    ) {
        if (costUsd != null
                || currency != null
                || priceVersion != null) {
            throw new IllegalArgumentException(
                    status
                            + " не должен содержать price metadata"
            );
        }
    }

    private static void requirePriceIdentity(
            String currency,
            String priceVersion
    ) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "currency обязателен для рассчитанной цены"
            );
        }

        if (priceVersion == null
                || priceVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "priceVersion обязателен для рассчитанной цены"
            );
        }
    }
}

