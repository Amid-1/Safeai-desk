package ru.safeai.gateway.ai.pricing;

import ru.safeai.gateway.ai.metadata.PricingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record PricingResult(
        PricingStatus status,
        BigDecimal costUsd,
        String currency,
        String priceVersion,
        Instant calculatedAt
) {
    private static final int MAX_COST_SCALE = 12;

    public PricingResult {
        Objects.requireNonNull(
                status,
                "status не должен быть null"
        );
        Objects.requireNonNull(
                calculatedAt,
                "calculatedAt не должен быть null"
        );

        if (costUsd != null) {
            if (costUsd.signum() < 0) {
                throw new IllegalArgumentException(
                        "costUsd не может быть отрицательным"
                );
            }
            if (costUsd.scale() > MAX_COST_SCALE) {
                throw new IllegalArgumentException(
                        "costUsd должен иметь scale не более "
                                + MAX_COST_SCALE
                );
            }
        }

        currency = normalizeCurrency(currency);
        priceVersion = normalizeOptional(
                priceVersion,
                64,
                "priceVersion"
        );

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
            case PRICED -> {
                if (costUsd == null || costUsd.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "PRICED требует costUsd > 0"
                    );
                }
                requirePriceIdentity(currency, priceVersion);
            }
            case FREE -> {
                if (costUsd == null || costUsd.signum() != 0) {
                    throw new IllegalArgumentException(
                            "FREE требует costUsd = 0"
                    );
                }
                requirePriceIdentity(currency, priceVersion);
            }
            case UNPRICED,
                 CALCULATION_FAILED,
                 NOT_APPLICABLE -> {
                if (costUsd != null
                        || currency != null
                        || priceVersion != null) {
                    throw new IllegalArgumentException(
                            status + " не должен содержать price metadata"
                    );
                }
            }
        }
    }

    private static void requirePriceIdentity(
            String currency,
            String priceVersion
    ) {
        if (currency == null) {
            throw new IllegalArgumentException(
                    "currency обязателен для рассчитанной цены"
            );
        }
        if (priceVersion == null) {
            throw new IllegalArgumentException(
                    "priceVersion обязателен для рассчитанной цены"
            );
        }
    }

    private static String normalizeCurrency(String value) {
        String normalized = normalizeOptional(
                value,
                3,
                "currency"
        );

        if (normalized == null) {
            return null;
        }

        normalized = normalized.toUpperCase(Locale.ROOT);

        if (!"USD".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Текущая схема costUsd поддерживает только USD"
            );
        }

        return normalized;
    }

    private static String normalizeOptional(
            String value,
            int maxLength,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " превышает " + maxLength + " символов"
            );
        }

        return normalized;
    }
}
