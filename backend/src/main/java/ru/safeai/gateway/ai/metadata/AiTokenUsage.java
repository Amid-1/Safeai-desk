package ru.safeai.gateway.ai.metadata;

/**
 * Provider token-usage snapshot used for pricing decisions.
 *
 * <p>inputTokens/outputTokens preserve the provider's top-level usage
 * counters. cachedInputTokens/cacheWriteInputTokens preserve specialized
 * billing dimensions when the provider reports them.</p>
 *
 * <p>specializedBillingDimensionsPresent distinguishes an absent billing
 * dimension from a malformed one. specializedBillingDimensionsValid=false
 * means the provider returned specialized billing metadata that could not be
 * interpreted safely, therefore pricing must fail closed instead of silently
 * treating the dimension as zero or absent.</p>
 */
public record AiTokenUsage(
        Integer inputTokens,
        Integer cachedInputTokens,
        Integer cacheWriteInputTokens,
        Integer outputTokens,
        boolean specializedBillingDimensionsPresent,
        boolean specializedBillingDimensionsValid
) {

    public AiTokenUsage {
        if (!specializedBillingDimensionsPresent) {
            if (cachedInputTokens != null
                    || cacheWriteInputTokens != null) {

                throw new IllegalArgumentException(
                        "Отсутствующие specialized billing dimensions "
                                + "не могут содержать token counters"
                );
            }

            if (!specializedBillingDimensionsValid) {
                throw new IllegalArgumentException(
                        "Отсутствующие specialized billing dimensions "
                                + "должны считаться валидными"
                );
            }
        }
    }

    public static AiTokenUsage basic(
            Integer inputTokens,
            Integer outputTokens
    ) {
        return new AiTokenUsage(
                inputTokens,
                null,
                null,
                outputTokens,
                false,
                true
        );
    }

    /**
     * Определяет полноту provider usage по top-level input/output counters.
     *
     * <p>Нулевые token counters являются валидными значениями и означают
     * AVAILABLE. Отсутствие обоих counters означает MISSING, отсутствие
     * ровно одного — PARTIAL.</p>
     */
    public UsageStatus usageStatus() {
        if (inputTokens == null
                && outputTokens == null) {

            return UsageStatus.MISSING;
        }

        if (inputTokens == null
                || outputTokens == null) {

            return UsageStatus.PARTIAL;
        }

        return UsageStatus.AVAILABLE;
    }

    /**
     * Показывает наличие specialized billing dimensions, которые текущая
     * flat pricing schema не умеет тарифицировать отдельно.
     *
     * <p>Нулевые specialized counters безопасны: они не изменяют стоимость
     * и поэтому не требуют перевода результата в UNPRICED.</p>
     */
    public boolean hasSpecializedBillableTokens() {
        return positive(
                cachedInputTokens
        ) || positive(
                cacheWriteInputTokens
        );
    }

    private static boolean positive(
            Integer value
    ) {
        return value != null
                && value > 0;
    }
}