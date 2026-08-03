package ru.safeai.gateway.usage.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageSummaryValueObjectsTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    @Test
    void allUnpricedMessagesKeepKnownCostZeroButMarkPricingIncomplete() {
        UsageCostSummary cost = new UsageCostSummary(
                BigDecimal.ZERO,
                0,
                0,
                3,
                0,
                0
        );

        assertThat(cost.knownCostUsd())
                .isEqualByComparingTo("0");

        assertThat(cost.unpricedMessages())
                .isEqualTo(3);

        assertThat(cost.pricingComplete())
                .isFalse();
    }

    @Test
    void mixedKnownAndUnknownPricingNeverPretendsKnownPartIsTotal() {
        UsageCostSummary cost = new UsageCostSummary(
                new BigDecimal("1.500000000000"),
                10,
                0,
                5,
                0,
                0
        );

        assertThat(cost.knownCostUsd())
                .isEqualByComparingTo("1.5");

        assertThat(cost.pricedMessages())
                .isEqualTo(10);

        assertThat(cost.unpricedMessages())
                .isEqualTo(5);

        assertThat(cost.pricingComplete())
                .isFalse();
    }

    @Test
    void freeMessagesAreKnownPricingAndMayHaveZeroCost() {
        UsageCostSummary cost = new UsageCostSummary(
                BigDecimal.ZERO,
                0,
                4,
                0,
                0,
                0
        );

        assertThat(cost.pricingComplete())
                .isTrue();

        assertThat(cost.currency())
                .isEqualTo("USD");
    }

    @Test
    void partialAndMissingUsageAreVisibleAndMarkCoverageIncomplete() {
        UsageTokenSummary usage = new UsageTokenSummary(
                100,
                50,
                7,
                0,
                9,
                1,
                2,
                0
        );

        assertThat(usage.confirmedTotalTokens())
                .isEqualTo(150);

        assertThat(usage.partialKnownTotalTokens())
                .isEqualTo(7);

        assertThat(usage.usageComplete())
                .isFalse();
    }

    @Test
    void dataQualityCalculatesIndependentUsageAndPricingCoverage() {
        UsageDataQualityResponse result =
                createIndependentCoverageResponse();

        assertThat(result.usageCoveragePercent())
                .isEqualByComparingTo("80.00");

        assertThat(result.pricingCoveragePercent())
                .isEqualByComparingTo("80.00");
    }

    @Test
    void aggregateRejectsUsageCountersThatDoNotCoverEveryAssistantMessage() {
        UsageResponseSummary responses =
                new UsageResponseSummary(
                        2,
                        2,
                        0,
                        0,
                        0
                );

        UsageTokenSummary invalidUsage =
                new UsageTokenSummary(
                        10,
                        10,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0
                );

        UsageCostSummary cost =
                new UsageCostSummary(
                        new BigDecimal("0.010000000000"),
                        2,
                        0,
                        0,
                        0,
                        0
                );

        assertThatThrownBy(() ->
                new UsageSummaryResponse(
                        USER_ID,
                        "user@example.com",
                        "gpt-test",
                        responses,
                        invalidUsage,
                        cost
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "usage status counters"
                );
    }

    @Test
    void dailyResponseMakesUtcContractExplicit() {
        UsageDailySummaryResponse result =
                createUtcDailySummaryResponse();

        assertThat(result.aggregationZone())
                .isEqualTo("UTC");
    }

    @Test
    void nonUsdCurrencyIsRejectedAtApiBoundary() {
        assertThatThrownBy(() ->
                new UsageCostSummary(
                        BigDecimal.ZERO,
                        "EUR",
                        0,
                        1,
                        0,
                        0,
                        0,
                        true
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "только USD"
                );
    }

    @Test
    void calculationFailureIsIndependentFromUnpricedStatus() {
        UsageCostSummary cost =
                new UsageCostSummary(
                        BigDecimal.ZERO,
                        0,
                        0,
                        0,
                        2,
                        0
                );

        assertThat(cost.pricingFailedMessages())
                .isEqualTo(2);

        assertThat(cost.unpricedMessages())
                .isZero();

        assertThat(cost.pricingComplete())
                .isFalse();
    }

    @Test
    void responseSummaryExposesUnclassifiedAssistantMessages() {
        UsageResponseSummary responses =
                new UsageResponseSummary(
                        5,
                        2,
                        1,
                        0,
                        1
                );

        assertThat(responses.unclassifiedMessages())
                .isEqualTo(1);
    }

    @Test
    void responseSummaryRejectsClassificationOverflow() {
        assertThatThrownBy(() ->
                new UsageResponseSummary(
                        1,
                        1,
                        1,
                        0,
                        0
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "превышает"
                );
    }

    @Test
    void tokenTotalsFailFastOnLongOverflow() {
        assertThatThrownBy(() ->
                new UsageTokenSummary(
                        Long.MAX_VALUE,
                        1,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0
                )
        )
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void emptyApplicablePopulationHasFullCoverageByDefinition() {
        UsageDataQualityResponse result =
                createEmptyApplicablePopulationResponse();

        assertThat(result.usageCoveragePercent())
                .isEqualByComparingTo("100.00");

        assertThat(result.pricingCoveragePercent())
                .isEqualByComparingTo("100.00");
    }

    private static UsageDataQualityResponse
    createIndependentCoverageResponse() {
        UsageTokenSummary usage =
                new UsageTokenSummary(
                        100,
                        50,
                        10,
                        0,
                        8,
                        1,
                        1,
                        0
                );

        UsageCostSummary cost =
                new UsageCostSummary(
                        new BigDecimal("0.250000000000"),
                        6,
                        2,
                        1,
                        1,
                        0
                );

        return new UsageDataQualityResponse(
                10,
                10,
                0,
                0,
                0,
                usage,
                cost,
                List.of()
        );
    }

    private static UsageDailySummaryResponse
    createUtcDailySummaryResponse() {
        UsageResponseSummary responses =
                new UsageResponseSummary(
                        1,
                        1,
                        0,
                        0,
                        0
                );

        UsageTokenSummary usage =
                new UsageTokenSummary(
                        1,
                        1,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0
                );

        UsageCostSummary cost =
                new UsageCostSummary(
                        BigDecimal.ZERO,
                        0,
                        1,
                        0,
                        0,
                        0
                );

        return new UsageDailySummaryResponse(
                LocalDate.of(2026, 8, 1),
                "UTC",
                responses,
                usage,
                cost
        );
    }

    private static UsageDataQualityResponse
    createEmptyApplicablePopulationResponse() {
        UsageTokenSummary usage =
                new UsageTokenSummary(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        1
                );

        UsageCostSummary cost =
                new UsageCostSummary(
                        BigDecimal.ZERO,
                        0,
                        0,
                        0,
                        0,
                        1
                );

        return new UsageDataQualityResponse(
                1,
                0,
                1,
                1,
                0,
                usage,
                cost,
                List.of()
        );
    }
}