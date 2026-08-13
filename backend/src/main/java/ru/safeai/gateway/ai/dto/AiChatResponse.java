package ru.safeai.gateway.ai.dto;

import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record AiChatResponse(
        String content,
        String requestedModel,
        String model,
        String providerMessageId,
        String providerRequestId,
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

    private static final int MAX_CONTENT_CHARS = 1_000_000;
    private static final int MAX_MODEL_CHARS = 100;
    private static final int MAX_PROVIDER_ID_CHARS = 255;
    private static final int MAX_FINISH_REASON_CHARS = 100;
    private static final int MAX_PRICE_VERSION_CHARS = 64;
    private static final int MAX_COST_SCALE = 12;

    public AiChatResponse {
        content = requireContent(content);

        requestedModel = requireModel(
                requestedModel,
                "requestedModel"
        );

        model = requireModel(
                model,
                "model"
        );

        providerMessageId = normalizeOptional(
                providerMessageId,
                MAX_PROVIDER_ID_CHARS,
                "providerMessageId"
        );

        providerRequestId = normalizeOptional(
                providerRequestId,
                MAX_PROVIDER_ID_CHARS,
                "providerRequestId"
        );

        finishReason = normalizeOptional(
                finishReason,
                MAX_FINISH_REASON_CHARS,
                "finishReason"
        );

        Objects.requireNonNull(
                responseStatus,
                "responseStatus не должен быть null"
        );

        validateToken(
                inputTokens,
                "inputTokens"
        );

        validateToken(
                outputTokens,
                "outputTokens"
        );

        Objects.requireNonNull(
                usageStatus,
                "usageStatus не должен быть null"
        );

        validateUsageStatus(
                usageStatus,
                inputTokens,
                outputTokens
        );

        Objects.requireNonNull(
                pricingStatus,
                "pricingStatus не должен быть null"
        );

        validateCost(costUsd);

        currency = normalizeCurrency(currency);

        priceVersion = normalizeOptional(
                priceVersion,
                MAX_PRICE_VERSION_CHARS,
                "priceVersion"
        );

        validatePricing(
                pricingStatus,
                costUsd,
                currency,
                priceVersion,
                pricingCalculatedAt,
                usageStatus
        );
    }

    /**
     * Каноническая фабрика для успешного ответа AI-провайдера.
     *
     * @param requestedModel    модель, которую запросило приложение
     * @param resolvedModel     фактическая модель из ответа провайдера
     * @param providerMessageId идентификатор AI response/message
     * @param providerRequestId HTTP request ID, выданный провайдером
     */
    public static AiChatResponse fromProvider(
            String content,
            String requestedModel,
            String resolvedModel,
            String providerMessageId,
            String providerRequestId,
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
                requestedModel,
                resolvedModel,
                providerMessageId,
                providerRequestId,
                responseStatus,
                finishReason,
                inputTokens,
                outputTokens,
                determineUsageStatus(
                        inputTokens,
                        outputTokens
                ),
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
            String fieldName
    ) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(
                    fieldName + " не может быть отрицательным"
            );
        }
    }

    private static void validateCost(
            BigDecimal costUsd
    ) {
        if (costUsd == null) {
            return;
        }

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
                boolean inputMissing = inputTokens == null;
                boolean outputMissing = outputTokens == null;

                if (inputMissing == outputMissing) {
                    throw new IllegalArgumentException(
                            "PARTIAL требует ровно один token counter"
                    );
                }
            }

            case NOT_APPLICABLE -> throw new IllegalArgumentException(
                    "Provider response не может иметь "
                            + "NOT_APPLICABLE usage"
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
            case PRICED -> validateCalculatedPricing(
                    costUsd,
                    currency,
                    priceVersion,
                    calculatedAt,
                    usageStatus,
                    false
            );

            case FREE -> validateCalculatedPricing(
                    costUsd,
                    currency,
                    priceVersion,
                    calculatedAt,
                    usageStatus,
                    true
            );

            case UNPRICED, CALCULATION_FAILED ->
                    validateAbsentPricing(
                            status,
                            costUsd,
                            currency,
                            priceVersion,
                            calculatedAt
                    );

            case NOT_APPLICABLE -> throw new IllegalArgumentException(
                    "Provider response не может иметь "
                            + "NOT_APPLICABLE pricing"
            );
        }
    }

    private static void validateCalculatedPricing(
            BigDecimal costUsd,
            String currency,
            String priceVersion,
            Instant calculatedAt,
            UsageStatus usageStatus,
            boolean free
    ) {
        Objects.requireNonNull(
                costUsd,
                "costUsd обязателен"
        );

        Objects.requireNonNull(
                currency,
                "currency обязательна"
        );

        Objects.requireNonNull(
                priceVersion,
                "priceVersion обязателен"
        );

        Objects.requireNonNull(
                calculatedAt,
                "pricingCalculatedAt обязателен"
        );

        if (usageStatus != UsageStatus.AVAILABLE) {
            throw new IllegalArgumentException(
                    (free ? "FREE" : "PRICED")
                            + " требует AVAILABLE usage"
            );
        }

        if (free && costUsd.signum() != 0) {
            throw new IllegalArgumentException(
                    "FREE должен иметь costUsd = 0"
            );
        }

        if (!free && costUsd.signum() <= 0) {
            throw new IllegalArgumentException(
                    "PRICED требует costUsd > 0"
            );
        }
    }

    private static void validateAbsentPricing(
            PricingStatus status,
            BigDecimal costUsd,
            String currency,
            String priceVersion,
            Instant calculatedAt
    ) {
        if (costUsd != null
                || currency != null
                || priceVersion != null) {
            throw new IllegalArgumentException(
                    status
                            + " не должен содержать price metadata"
            );
        }

        Objects.requireNonNull(
                calculatedAt,
                "pricingCalculatedAt обязателен"
        );
    }

    private static String requireContent(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "content не должен быть пустым"
            );
        }

        if (value.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "content превышает "
                            + MAX_CONTENT_CHARS
                            + " символов"
            );
        }

        /*
         * Контент намеренно не trim-ится:
         * пробелы и переносы могут быть значимой частью ответа модели.
         */
        return value;
    }

    private static String requireModel(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " не должен быть пустым"
            );
        }

        String normalized = value.trim();

        if (normalized.length() > MAX_MODEL_CHARS) {
            throw new IllegalArgumentException(
                    fieldName + " превышает "
                            + MAX_MODEL_CHARS
                            + " символов"
            );
        }

        return normalized;
    }

    private static String normalizeOptional(
            String value,
            int maxLength,
            String fieldName
    ) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " превышает "
                            + maxLength
                            + " символов"
            );
        }

        return normalized;
    }

    private static String normalizeCurrency(
            String value
    ) {
        String normalized = normalizeOptional(
                value,
                3,
                "currency"
        );

        if (normalized == null) {
            return null;
        }

        String upperCase =
                normalized.toUpperCase(Locale.ROOT);

        if (!"USD".equals(upperCase)) {
            throw new IllegalArgumentException(
                    "Текущая модель costUsd поддерживает только USD"
            );
        }

        return upperCase;
    }
}
