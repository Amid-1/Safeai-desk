package ru.safeai.gateway.usage.dto;

public record UsageTokenSummary(
        long confirmedInputTokens,
        long confirmedOutputTokens,
        long confirmedTotalTokens,
        long partialKnownInputTokens,
        long partialKnownOutputTokens,
        long partialKnownTotalTokens,
        long availableUsageMessages,
        long partialUsageMessages,
        long missingUsageMessages,
        long usageNotApplicableMessages,
        boolean usageComplete
) {

    public UsageTokenSummary {
        validateNonNegative(
                confirmedInputTokens,
                "confirmedInputTokens"
        );

        validateNonNegative(
                confirmedOutputTokens,
                "confirmedOutputTokens"
        );

        validateNonNegative(
                partialKnownInputTokens,
                "partialKnownInputTokens"
        );

        validateNonNegative(
                partialKnownOutputTokens,
                "partialKnownOutputTokens"
        );

        validateNonNegative(
                availableUsageMessages,
                "availableUsageMessages"
        );

        validateNonNegative(
                partialUsageMessages,
                "partialUsageMessages"
        );

        validateNonNegative(
                missingUsageMessages,
                "missingUsageMessages"
        );

        validateNonNegative(
                usageNotApplicableMessages,
                "usageNotApplicableMessages"
        );

        confirmedTotalTokens = Math.addExact(
                confirmedInputTokens,
                confirmedOutputTokens
        );

        partialKnownTotalTokens = Math.addExact(
                partialKnownInputTokens,
                partialKnownOutputTokens
        );

        usageComplete = partialUsageMessages == 0L
                && missingUsageMessages == 0L;
    }

    public UsageTokenSummary(
            long confirmedInputTokens,
            long confirmedOutputTokens,
            long partialKnownInputTokens,
            long partialKnownOutputTokens,
            long availableUsageMessages,
            long partialUsageMessages,
            long missingUsageMessages,
            long usageNotApplicableMessages
    ) {
        this(
                confirmedInputTokens,
                confirmedOutputTokens,
                0L,
                partialKnownInputTokens,
                partialKnownOutputTokens,
                0L,
                availableUsageMessages,
                partialUsageMessages,
                missingUsageMessages,
                usageNotApplicableMessages,
                false
        );
    }

    public long classifiedMessages() {
        long availableOrPartialMessages = Math.addExact(
                availableUsageMessages,
                partialUsageMessages
        );

        long missingOrNotApplicableMessages = Math.addExact(
                missingUsageMessages,
                usageNotApplicableMessages
        );

        return Math.addExact(
                availableOrPartialMessages,
                missingOrNotApplicableMessages
        );
    }

    private static void validateNonNegative(
            long value,
            String propertyName
    ) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    propertyName + " не может быть отрицательным"
            );
        }
    }
}