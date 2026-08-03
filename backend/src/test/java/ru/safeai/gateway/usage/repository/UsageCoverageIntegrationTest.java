package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(
        properties = "safeai.usage.rollup.enabled=false"
)
@ActiveProfiles("test")
class UsageCoverageIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final String MODEL =
            "coverage-model";

    private static final String UNATTRIBUTED_MODEL =
            "__unattributed__";

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-06-20T12:00:00Z"
            );

    @Autowired
    private UsageQueryRepository repository;

    @Test
    void availableUsageContributesToConfirmedTokenTotals() {
        completed(
                "COMPLETED",
                100,
                50,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE"
        );

        UsageModelSummaryResponse result =
                modelResult();

        assertThat(
                result.usage().confirmedInputTokens()
        ).isEqualTo(100);

        assertThat(
                result.usage().confirmedOutputTokens()
        ).isEqualTo(50);

        assertThat(
                result.usage().confirmedTotalTokens()
        ).isEqualTo(150);

        assertThat(
                result.usage().usageComplete()
        ).isTrue();
    }

    @Test
    void missingUsageRemainsVisibleInQualityStatistics() {
        completed(
                "COMPLETED",
                null,
                null,
                "MISSING",
                null,
                "UNPRICED"
        );

        UsageDataQualityResponse quality =
                qualityResult();

        assertThat(
                quality.usage().missingUsageMessages()
        ).isEqualTo(1);

        assertThat(
                quality.usage().confirmedTotalTokens()
        ).isZero();

        assertThat(
                quality.usage().usageComplete()
        ).isFalse();

        assertThat(
                quality.usageCoveragePercent()
        ).isEqualByComparingTo("0.00");

        assertThat(quality.problemModels())
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.model())
                            .isEqualTo(MODEL);

                    assertThat(problem.usageProblems())
                            .isEqualTo(1);

                    assertThat(problem.pricingProblems())
                            .isEqualTo(1);
                });
    }

    @Test
    void partialUsagePreservesKnownCountersWithoutInflatingConfirmedTotals() {
        completed(
                "COMPLETED",
                75,
                null,
                "PARTIAL",
                null,
                "UNPRICED"
        );

        UsageModelSummaryResponse result =
                modelResult();

        assertThat(
                result.usage().confirmedTotalTokens()
        ).isZero();

        assertThat(
                result.usage().partialKnownInputTokens()
        ).isEqualTo(75);

        assertThat(
                result.usage().partialKnownOutputTokens()
        ).isZero();

        assertThat(
                result.usage().partialKnownTotalTokens()
        ).isEqualTo(75);

        assertThat(
                result.usage().partialUsageMessages()
        ).isEqualTo(1);

        assertThat(
                result.usage().usageComplete()
        ).isFalse();
    }

    @Test
    void refusedResponseWithProviderCountersIsIncluded() {
        completed(
                "REFUSED",
                11,
                7,
                "AVAILABLE",
                new BigDecimal("0.001000000000"),
                "PRICED"
        );

        UsageModelSummaryResponse result =
                modelResult();

        assertThat(
                result.responses().assistantMessages()
        ).isEqualTo(1);

        assertThat(
                result.responses().refusedResponses()
        ).isEqualTo(1);

        assertThat(
                result.responses().completedResponses()
        ).isZero();

        assertThat(
                result.usage().confirmedTotalTokens()
        ).isEqualTo(18);

        assertThat(
                result.cost().knownCostUsd()
        ).isEqualByComparingTo(
                "0.001000000000"
        );
    }

    @Test
    void incompleteResponseWithProviderCountersIsIncluded() {
        completed(
                "INCOMPLETE",
                20,
                5,
                "AVAILABLE",
                new BigDecimal("0.002000000000"),
                "PRICED"
        );

        UsageModelSummaryResponse result =
                modelResult();

        assertThat(
                result.responses().incompleteResponses()
        ).isEqualTo(1);

        assertThat(
                result.usage().confirmedTotalTokens()
        ).isEqualTo(25);
    }

    @Test
    void failedStoredMessageIsNotCountedAsSuccessfulUsage() {
        insertFailedAssistant(CREATED_AT);

        UsageDataQualityResponse quality =
                qualityResult();

        assertThat(
                quality.assistantMessages()
        ).isEqualTo(1);

        assertThat(
                quality.storedFailedMessages()
        ).isEqualTo(1);

        assertThat(
                quality.usage().confirmedTotalTokens()
        ).isZero();

        assertThat(
                quality.usage()
                        .usageNotApplicableMessages()
        ).isEqualTo(1);

        assertThat(
                quality.cost()
                        .pricingNotApplicableMessages()
        ).isEqualTo(1);
    }

    @Test
    void missingResolvedModelIsReportedAndUsesReservedDisplayBucket() {
        insertFailedAssistant(CREATED_AT);

        UsageDataQualityResponse quality =
                qualityResult();

        List<UsageModelSummaryResponse> models =
                repository.findModels(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                null,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(
                                RANGE_FROM,
                                RANGE_TO
                        )
                );

        List<UsageModelSummaryResponse> filtered =
                repository.findModels(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                UNATTRIBUTED_MODEL,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(
                                RANGE_FROM,
                                RANGE_TO
                        )
                );

        assertThat(
                quality.missingResolvedModelMessages()
        ).isEqualTo(1);

        assertThat(models)
                .extracting(
                        UsageModelSummaryResponse::model
                )
                .containsExactly(
                        UNATTRIBUTED_MODEL
                );

        assertThat(filtered)
                .extracting(
                        UsageModelSummaryResponse::model
                )
                .containsExactly(
                        UNATTRIBUTED_MODEL
                );
    }

    private UsageModelSummaryResponse modelResult() {
        List<UsageModelSummaryResponse> rows =
                repository.findModels(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                MODEL,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(
                                RANGE_FROM,
                                RANGE_TO
                        )
                );

        assertThat(rows)
                .hasSize(1);

        return rows.getFirst();
    }

    private UsageDataQualityResponse qualityResult() {
        return repository.findDataQuality(
                criteria(
                        ORGANIZATION_A_ID,
                        null,
                        null,
                        RANGE_FROM,
                        RANGE_TO
                ),
                livePlan(
                        RANGE_FROM,
                        RANGE_TO
                )
        );
    }

    private void completed(
            String responseStatus,
            Integer inputTokens,
            Integer outputTokens,
            String usageStatus,
            BigDecimal cost,
            String pricingStatus
    ) {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                responseStatus,
                inputTokens,
                outputTokens,
                usageStatus,
                cost,
                pricingStatus,
                CREATED_AT
        );
    }
}