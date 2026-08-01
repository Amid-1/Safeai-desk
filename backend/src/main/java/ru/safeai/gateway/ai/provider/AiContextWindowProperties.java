package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.ai.context")
public record AiContextWindowProperties(
        Integer maxUserMessageChars,
        Integer maxInstructionChars,
        Integer maxHistoryMessages,
        Integer maxMessageChars,
        Integer maxTotalInputChars,
        Integer charsPerEstimatedToken,
        Integer messageOverheadTokens,
        Integer safetyMarginTokens
) {
    public AiContextWindowProperties {
        maxUserMessageChars = range(
                maxUserMessageChars,
                16_000,
                1,
                100_000,
                "max-user-message-chars"
        );
        maxInstructionChars = range(
                maxInstructionChars,
                32_000,
                1,
                100_000,
                "max-instruction-chars"
        );
        maxHistoryMessages = range(
                maxHistoryMessages,
                100,
                0,
                1_000,
                "max-history-messages"
        );
        maxMessageChars = range(
                maxMessageChars,
                50_000,
                1,
                100_000,
                "max-message-chars"
        );
        maxTotalInputChars = range(
                maxTotalInputChars,
                250_000,
                1_000,
                1_000_000,
                "max-total-input-chars"
        );
        charsPerEstimatedToken = range(
                charsPerEstimatedToken,
                3,
                1,
                8,
                "chars-per-estimated-token"
        );
        messageOverheadTokens = range(
                messageOverheadTokens,
                8,
                0,
                128,
                "message-overhead-tokens"
        );
        safetyMarginTokens = range(
                safetyMarginTokens,
                1_024,
                0,
                32_768,
                "safety-margin-tokens"
        );
    }

    public static AiContextWindowProperties defaults() {
        return new AiContextWindowProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static int range(
            Integer value,
            int defaultValue,
            int min,
            int max,
            String name
    ) {
        int effective = value == null ? defaultValue : value;

        if (effective < min || effective > max) {
            throw new IllegalStateException(
                    "safeai.ai.context." + name
                            + " должен быть в диапазоне "
                            + min + "–" + max
            );
        }

        return effective;
    }
}
