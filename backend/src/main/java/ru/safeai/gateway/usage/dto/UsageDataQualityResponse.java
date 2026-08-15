package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

public record UsageDataQualityResponse(
        long assistantMessages,
        long storedCompletedMessages,
        long storedFailedMessages,
        long missingResolvedModelMessages,
        long ambiguousProviderOperations,
        UsageTokenSummary usage,
        UsageCostSummary cost,
        BigDecimal usageCoveragePercent,
        BigDecimal pricingCoveragePercent,
        List<UsageProblemModelResponse> problemModels
) {

    private static final BigDecimal HUNDRED =
            new BigDecimal("100");

    private static final BigDecimal FULL_COVERAGE_PERCENT =
            new BigDecimal("100.00");

    public UsageDataQualityResponse {
        validateNonNegative(
                assistantMessages,
                "assistantMessages"
        );
        validateNonNegative(
                storedCompletedMessages,
                "storedCompletedMessages"
        );
        validateNonNegative(
                storedFailedMessages,
                "storedFailedMessages"
        );
        validateNonNegative(
                missingResolvedModelMessages,
                "missingResolvedModelMessages"
        );
        validateNonNegative(
                ambiguousProviderOperations,
                "ambiguousProviderOperations"
        );

        Objects.requireNonNull(
                usage,
                "usage не должен быть null"
        );
        Objects.requireNonNull(
                cost,
                "cost не должен быть null"
        );

        UsageSummaryInvariants.validate(
                assistantMessages,
                usage,
                cost
        );

        problemModels = List.copyOf(
                Objects.requireNonNull(
                        problemModels,
                        "problemModels не должен быть null"
                )
        );

        long usageApplicableMessages = Math.addExact(
                usage.availableUsageMessages(),
                Math.addExact(
                        usage.partialUsageMessages(),
                        usage.missingUsageMessages()
                )
        );

        usageCoveragePercent = coveragePercent(
                usage.availableUsageMessages(),
                usageApplicableMessages
        );

        long pricedOrFreeMessages = Math.addExact(
                cost.pricedMessages(),
                cost.freeMessages()
        );

        long incompletePricingMessages = Math.addExact(
                cost.unpricedMessages(),
                cost.pricingFailedMessages()
        );

        long pricingApplicableMessages = Math.addExact(
                pricedOrFreeMessages,
                incompletePricingMessages
        );

        pricingCoveragePercent = coveragePercent(
                pricedOrFreeMessages,
                pricingApplicableMessages
        );
    }

    public UsageDataQualityResponse(
            long assistantMessages,
            long storedCompletedMessages,
            long storedFailedMessages,
            long missingResolvedModelMessages,
            long ambiguousProviderOperations,
            UsageTokenSummary usage,
            UsageCostSummary cost,
            List<UsageProblemModelResponse> problemModels
    ) {
        this(
                assistantMessages,
                storedCompletedMessages,
                storedFailedMessages,
                missingResolvedModelMessages,
                ambiguousProviderOperations,
                usage,
                cost,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                problemModels
        );
    }

    private static BigDecimal coveragePercent(
            long covered,
            long applicable
    ) {
        if (applicable == 0L) {
            return FULL_COVERAGE_PERCENT;
        }

        return BigDecimal.valueOf(covered)
                .multiply(HUNDRED)
                .divide(
                        BigDecimal.valueOf(applicable),
                        2,
                        RoundingMode.HALF_UP
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
