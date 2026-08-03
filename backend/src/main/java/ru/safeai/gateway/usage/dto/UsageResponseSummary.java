package ru.safeai.gateway.usage.dto;

public record UsageResponseSummary(
        long assistantMessages,
        long completedResponses,
        long refusedResponses,
        long incompleteResponses,
        long failedMessages,
        long unclassifiedMessages
) {

    public UsageResponseSummary {
        validateNonNegative(
                assistantMessages,
                "assistantMessages"
        );
        validateNonNegative(
                completedResponses,
                "completedResponses"
        );
        validateNonNegative(
                refusedResponses,
                "refusedResponses"
        );
        validateNonNegative(
                incompleteResponses,
                "incompleteResponses"
        );
        validateNonNegative(
                failedMessages,
                "failedMessages"
        );

        long completedOrRefusedResponses = Math.addExact(
                completedResponses,
                refusedResponses
        );

        long classifiedProviderResponses = Math.addExact(
                completedOrRefusedResponses,
                incompleteResponses
        );

        long classifiedStoredMessages = Math.addExact(
                classifiedProviderResponses,
                failedMessages
        );

        if (classifiedStoredMessages > assistantMessages) {
            throw new IllegalArgumentException(
                    "Количество классифицированных assistant messages "
                            + "превышает assistantMessages"
            );
        }

        unclassifiedMessages =
                assistantMessages - classifiedStoredMessages;
    }

    public UsageResponseSummary(
            long assistantMessages,
            long completedResponses,
            long refusedResponses,
            long incompleteResponses,
            long failedMessages
    ) {
        this(
                assistantMessages,
                completedResponses,
                refusedResponses,
                incompleteResponses,
                failedMessages,
                0L
        );
    }

    private static void validateNonNegative(
            long value,
            String propertyName
    ) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    propertyName
                            + " не может быть отрицательным"
            );
        }
    }
}