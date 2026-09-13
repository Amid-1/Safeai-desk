package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.UsageStatus;

/** Complete provider-reported usage, kept independently from chat_messages. */
public record ProviderUsageEvidence(
        Integer inputTokens,
        Integer cachedInputTokens,
        Integer cacheWriteInputTokens,
        Integer outputTokens,
        UsageStatus usageStatus,
        boolean specializedDimensionsPresent,
        boolean specializedDimensionsValid
) {
    public ProviderUsageEvidence {
        validate(inputTokens, "inputTokens");
        validate(cachedInputTokens, "cachedInputTokens");
        validate(cacheWriteInputTokens, "cacheWriteInputTokens");
        validate(outputTokens, "outputTokens");
        if (!specializedDimensionsPresent && (cachedInputTokens != null || cacheWriteInputTokens != null)) {
            throw new IllegalArgumentException("Absent specialized dimensions cannot contain counters");
        }
    }

    public static ProviderUsageEvidence from(AiChatResponse response) {
        return new ProviderUsageEvidence(
                response.inputTokens(), response.cachedInputTokens(),
                response.cacheWriteInputTokens(), response.outputTokens(),
                response.usageStatus(), response.specializedBillingDimensionsPresent(),
                response.specializedBillingDimensionsValid()
        );
    }

    private static void validate(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " не может быть отрицательным");
        }
    }
}
