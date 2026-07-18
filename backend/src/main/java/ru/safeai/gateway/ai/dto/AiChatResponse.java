package ru.safeai.gateway.ai.dto;

import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AiChatResponse(
        String content,
        String model,
        String providerMessageId,
        AiResponseStatus responseStatus,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens,
        UsageStatus usageStatus,
        BigDecimal costUsd,
        PricingStatus pricingStatus,
        String currency,
        String priceVersion,
        Instant pricingCalculatedAt
) {
    public AiChatResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "AI response content не должен быть пустым"
            );
        }

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "AI response model не должен быть пустым"
            );
        }

        model = model.trim();
        providerMessageId = normalizeOptional(providerMessageId);
        finishReason = normalizeOptional(finishReason);

        responseStatus = Objects.requireNonNull(
                responseStatus,
                "responseStatus не должен быть null"
        );

        validateToken(inputTokens, "inputTokens");
        validateToken(outputTokens, "outputTokens");

        usageStatus = Objects.requireNonNull(
                usageStatus,
                "usageStatus не должен быть null"
        );

        validateUsageStatus(
                usageStatus,
                inputTokens,
                outputTokens
        );

        pricingStatus = Objects.requireNonNull(
                pricingStatus,
                "pricingStatus не должен быть null"
        );

        if (costUsd != null && costUsd.signum() < 0) {
            throw new IllegalArgumentException(
                    "costUsd не может быть отрицательным"
            );
        }

        currency = normalizeCurrency(currency);
        priceVersion = normalizeOptional(priceVersion);

        validatePricing(
                pricingStatus,
                costUsd,
                currency,
                priceVersion,
                pricingCalculatedAt,
                usageStatus
        );
    }

    public static AiChatResponse fromProvider(
            String content,
            String model,
            String providerMessageId,
            AiResponseStatus responseStatus,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            PricingResult pricing
    ) {
        Objects.requireNonNull(
                pricing,
                "pricing не должен быть null"
        );

        return new AiChatResponse(
                content,
                model,
                providerMessageId,
                responseStatus,
                finishReason,
                inputTokens,
                outputTokens,
                determineUsageStatus(inputTokens, outputTokens),
                pricing.costUsd(),
                pricing.status(),
                pricing.currency(),
                pricing.priceVersion(),
                pricing.calculatedAt()
        );
    }

    public static UsageStatus determineUsageStatus(
            Integer inputTokens,
            Integer outputTokens
    ) {
        if (inputTokens == null && outputTokens == null) {
            return UsageStatus.MISSING;
        }

        if (inputTokens == null || outputTokens == null) {
            return UsageStatus.PARTIAL;
        }

        return UsageStatus.AVAILABLE;
    }

    private static void validateToken(
            Integer value,
            String name
    ) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(
                    name + " не может быть отрицательным"
            );
        }
    }

    private static void validateUsageStatus(
            UsageStatus status,
            Integer inputTokens,
            Integer outputTokens
    ) {
        switch (status) {
            case AVAILABLE -> {
                if (inputTokens == null || outputTokens == null) {
                    throw new IllegalArgumentException(
                            "AVAILABLE требует оба token counter"
                    );
                }
            }
            case MISSING -> {
                if (inputTokens != null || outputTokens != null) {
                    throw new IllegalArgumentException(
                            "MISSING требует отсутствующие token counters"
                    );
                }
            }
            case PARTIAL -> {
                if ((inputTokens == null) == (outputTokens == null)) {
                    throw new IllegalArgumentException(
                            "PARTIAL требует ровно один token counter"
                    );
                }
            }
            case NOT_APPLICABLE -> throw new IllegalArgumentException(
                    "Provider response не может иметь NOT_APPLICABLE usage"
            );
        }
    }

    private static void validatePricing(
            PricingStatus status,
            BigDecimal costUsd,
            String currency,
            String priceVersion,
            Instant calculatedAt,
            UsageStatus usageStatus
    ) {
        switch (status) {
            case PRICED, FREE -> {
                Objects.requireNonNull(costUsd, "costUsd обязателен");
                Objects.requireNonNull(currency, "currency обязательна");
                Objects.requireNonNull(priceVersion, "priceVersion обязателен");
                Objects.requireNonNull(
                        calculatedAt,
                        "pricingCalculatedAt обязателен"
                );

                if (usageStatus != UsageStatus.AVAILABLE) {
                    throw new IllegalArgumentException(
                            "PRICED/FREE требуют AVAILABLE usage"
                    );
                }

                if (status == PricingStatus.FREE
                        && costUsd.signum() != 0) {
                    throw new IllegalArgumentException(
                            "FREE должен иметь costUsd = 0"
                    );
                }
            }
            case UNPRICED, CALCULATION_FAILED -> {
                if (costUsd != null) {
                    throw new IllegalArgumentException(
                            status + " не должен содержать costUsd"
                    );
                }
                Objects.requireNonNull(
                        calculatedAt,
                        "pricingCalculatedAt обязателен"
                );
            }
            case NOT_APPLICABLE -> throw new IllegalArgumentException(
                    "Provider response не может иметь NOT_APPLICABLE pricing"
            );
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeCurrency(String value) {
        String normalized = normalizeOptional(value);

        if (normalized == null) {
            return null;
        }

        normalized = normalized.toUpperCase();

        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency должен быть ISO-4217 кодом"
            );
        }

        return normalized;
    }
}
