package ru.safeai.gateway.ai.metadata;

/**
 * Canonical provider token-usage snapshot used by pricing and durable evidence.
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
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(cachedInputTokens, "cachedInputTokens");
        requireNonNegative(cacheWriteInputTokens, "cacheWriteInputTokens");
        requireNonNegative(outputTokens, "outputTokens");

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

    public UsageStatus usageStatus() {
        if (inputTokens == null && outputTokens == null) {
            return UsageStatus.MISSING;
        }

        if (inputTokens == null || outputTokens == null) {
            return UsageStatus.PARTIAL;
        }

        return UsageStatus.AVAILABLE;
    }

    public boolean hasSpecializedBillableTokens() {
        return positive(cachedInputTokens)
                || positive(cacheWriteInputTokens);
    }

    private static void requireNonNegative(
            Integer value,
            String field
    ) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(
                    field + " не может быть отрицательным"
            );
        }
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
