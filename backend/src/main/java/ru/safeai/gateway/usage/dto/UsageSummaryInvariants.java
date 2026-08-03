package ru.safeai.gateway.usage.dto;

final class UsageSummaryInvariants {

    private UsageSummaryInvariants() {
    }

    static void validate(
            UsageResponseSummary responses,
            UsageTokenSummary usage,
            UsageCostSummary cost
    ) {
        validate(
                responses.assistantMessages(),
                usage,
                cost
        );
    }

    static void validate(
            long assistantMessages,
            UsageTokenSummary usage,
            UsageCostSummary cost
    ) {
        if (usage.classifiedMessages() != assistantMessages) {
            throw new IllegalArgumentException(
                    "Сумма usage status counters должна быть равна "
                            + "assistantMessages"
            );
        }

        if (cost.classifiedMessages() != assistantMessages) {
            throw new IllegalArgumentException(
                    "Сумма pricing status counters должна быть равна "
                            + "assistantMessages"
            );
        }
    }
}
