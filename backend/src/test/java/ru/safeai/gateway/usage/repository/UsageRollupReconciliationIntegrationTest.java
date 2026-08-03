package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.service.UsageRollupDayProcessor;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(
        properties = "safeai.usage.rollup.enabled=false"
)
@ActiveProfiles("test")
class UsageRollupReconciliationIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final LocalDate ROLLUP_DATE =
            LocalDate.of(2026, 6, 10);

    private static final String MODEL =
            "rollup-model";

    @Autowired
    private UsageQueryRepository repository;

    @Autowired
    private UsageRollupDayProcessor dayProcessor;

    @Autowired
    private UsageRollupStateRepository stateRepository;

    @Test
    void rollupMatchesSourceChatMessagesForAllCounters() {
        insertMixedSourceRows();

        UsageModelSummaryResponse live =
                liveResult();

        dayProcessor.rebuildDay(
                ROLLUP_DATE,
                true
        );

        UsageModelSummaryResponse rollup =
                rollupResult();

        assertSameAggregate(
                rollup,
                live
        );

        assertThat(
                stateRepository.findLastCompletedDate()
        ).isEqualTo(ROLLUP_DATE);
    }

    @Test
    void rebuildingSameDayIsIdempotent() {
        insertMixedSourceRows();

        dayProcessor.rebuildDay(
                ROLLUP_DATE,
                true
        );

        long userRowsAfterFirst =
                countRows(
                        "usage_daily_user_model_rollups"
                );

        long orgRowsAfterFirst =
                countRows(
                        "usage_daily_org_model_rollups"
                );

        long qualityRowsAfterFirst =
                countRows(
                        "usage_daily_quality_rollups"
                );

        dayProcessor.rebuildDay(
                ROLLUP_DATE,
                true
        );

        assertThat(
                countRows(
                        "usage_daily_user_model_rollups"
                )
        ).isEqualTo(userRowsAfterFirst);

        assertThat(
                countRows(
                        "usage_daily_org_model_rollups"
                )
        ).isEqualTo(orgRowsAfterFirst);

        assertThat(
                countRows(
                        "usage_daily_quality_rollups"
                )
        ).isEqualTo(qualityRowsAfterFirst);

        assertSameAggregate(
                rollupResult(),
                liveResult()
        );
    }

    @Test
    void reconciliationReprocessesLateArrivingRows() {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                new BigDecimal("0.100000000000"),
                "PRICED",
                instant(
                        "2026-06-10T10:00:00Z"
                )
        );

        dayProcessor.rebuildDay(
                ROLLUP_DATE,
                true
        );

        assertThat(
                rollupResult()
                        .responses()
                        .assistantMessages()
        ).isEqualTo(1);

        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "INCOMPLETE",
                30,
                40,
                "AVAILABLE",
                new BigDecimal("0.200000000000"),
                "PRICED",
                instant(
                        "2026-06-10T23:59:00Z"
                )
        );

        /*
         * Lookback reconciliation must rebuild the day without moving
         * the contiguous watermark backwards or creating duplicate rows.
         */
        dayProcessor.rebuildDay(
                ROLLUP_DATE,
                false
        );

        UsageModelSummaryResponse result =
                rollupResult();

        assertThat(
                result.responses().assistantMessages()
        ).isEqualTo(2);

        assertThat(
                result.responses().incompleteResponses()
        ).isEqualTo(1);

        assertThat(
                result.usage().confirmedTotalTokens()
        ).isEqualTo(100);

        assertThat(
                result.cost().knownCostUsd()
        ).isEqualByComparingTo(
                "0.300000000000"
        );

        assertThat(
                stateRepository.findLastCompletedDate()
        ).isEqualTo(ROLLUP_DATE);
    }

    @Test
    void hybridPlanDoesNotDoubleCountClosedAndLiveParts() {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE",
                instant(
                        "2026-06-10T12:00:00Z"
                )
        );

        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                30,
                40,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE",
                instant(
                        "2026-06-11T12:00:00Z"
                )
        );

        dayProcessor.rebuildDay(
                ROLLUP_DATE,
                true
        );

        UsageQueryCriteria criteria =
                criteria(
                        ORGANIZATION_A_ID,
                        null,
                        MODEL,
                        instant(
                                "2026-06-10T00:00:00Z"
                        ),
                        instant(
                                "2026-06-12T00:00:00Z"
                        )
                );

        UsageQueryPlan plan =
                hybridPlan(
                        ROLLUP_DATE,
                        ROLLUP_DATE.plusDays(1),
                        instant(
                                "2026-06-11T00:00:00Z"
                        ),
                        instant(
                                "2026-06-12T00:00:00Z"
                        )
                );

        List<UsageModelSummaryResponse> rows =
                repository.findModels(
                        criteria,
                        plan
                );

        assertThat(rows)
                .hasSize(1);

        UsageModelSummaryResponse result =
                rows.getFirst();

        assertThat(
                result.responses().assistantMessages()
        ).isEqualTo(2);

        assertThat(
                result.usage().confirmedInputTokens()
        ).isEqualTo(40);

        assertThat(
                result.usage().confirmedOutputTokens()
        ).isEqualTo(60);
    }

    @Test
    void watermarkIsMonotonic() {
        stateRepository.markCompleted(
                LocalDate.of(2026, 6, 12)
        );

        stateRepository.markCompleted(
                LocalDate.of(2026, 6, 10)
        );

        assertThat(
                stateRepository.findLastCompletedDate()
        ).isEqualTo(
                LocalDate.of(2026, 6, 12)
        );
    }

    @Test
    void watermarkRejectsSkippedUtcDay() {
        stateRepository.markCompleted(
                LocalDate.of(2026, 6, 10)
        );

        assertThatThrownBy(() ->
                stateRepository.markCompleted(
                        LocalDate.of(2026, 6, 12)
                )
        )
                .isInstanceOf(
                        InvalidDataAccessApiUsageException.class
                )
                .hasMessageContaining(
                        "пропуском UTC-дня"
                )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Usage rollup watermark нельзя продвинуть "
                                + "с пропуском UTC-дня: 2026-06-12"
                );

        assertThat(
                stateRepository.findLastCompletedDate()
        ).isEqualTo(
                LocalDate.of(2026, 6, 10)
        );
    }

    private void insertMixedSourceRows() {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                100,
                50,
                "AVAILABLE",
                new BigDecimal("1.000000000000"),
                "PRICED",
                instant(
                        "2026-06-10T01:00:00Z"
                )
        );

        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "REFUSED",
                20,
                10,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE",
                instant(
                        "2026-06-10T02:00:00Z"
                )
        );

        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "INCOMPLETE",
                7,
                null,
                "PARTIAL",
                null,
                "UNPRICED",
                instant(
                        "2026-06-10T03:00:00Z"
                )
        );

        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                null,
                null,
                "MISSING",
                null,
                "CALCULATION_FAILED",
                instant(
                        "2026-06-10T04:00:00Z"
                )
        );

        insertFailedAssistant(
                instant(
                        "2026-06-10T05:00:00Z"
                )
        );
    }

    private UsageModelSummaryResponse liveResult() {
        Instant dateFrom =
                utcStart(ROLLUP_DATE);

        Instant dateTo =
                utcStart(
                        ROLLUP_DATE.plusDays(1)
                );

        List<UsageModelSummaryResponse> rows =
                repository.findModels(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                MODEL,
                                dateFrom,
                                dateTo
                        ),
                        livePlan(
                                dateFrom,
                                dateTo
                        )
                );

        assertThat(rows)
                .hasSize(1);

        return rows.getFirst();
    }

    private UsageModelSummaryResponse rollupResult() {
        Instant dateFrom =
                utcStart(ROLLUP_DATE);

        Instant dateTo =
                utcStart(
                        ROLLUP_DATE.plusDays(1)
                );

        List<UsageModelSummaryResponse> rows =
                repository.findModels(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                MODEL,
                                dateFrom,
                                dateTo
                        ),
                        rollupPlan(
                                ROLLUP_DATE
                        )
                );

        assertThat(rows)
                .hasSize(1);

        return rows.getFirst();
    }

    private void assertSameAggregate(
            UsageModelSummaryResponse actual,
            UsageModelSummaryResponse expected
    ) {
        assertThat(actual.model())
                .isEqualTo(expected.model());

        assertThat(actual.responses())
                .isEqualTo(expected.responses());

        assertThat(actual.usage())
                .isEqualTo(expected.usage());

        assertThat(
                actual.cost().pricedMessages()
        ).isEqualTo(
                expected.cost().pricedMessages()
        );

        assertThat(
                actual.cost().freeMessages()
        ).isEqualTo(
                expected.cost().freeMessages()
        );

        assertThat(
                actual.cost().unpricedMessages()
        ).isEqualTo(
                expected.cost().unpricedMessages()
        );

        assertThat(
                actual.cost().pricingFailedMessages()
        ).isEqualTo(
                expected.cost().pricingFailedMessages()
        );

        assertThat(
                actual.cost()
                        .pricingNotApplicableMessages()
        ).isEqualTo(
                expected.cost()
                        .pricingNotApplicableMessages()
        );

        assertThat(
                actual.cost().knownCostUsd()
        ).isEqualByComparingTo(
                expected.cost().knownCostUsd()
        );

        assertThat(
                actual.cost().pricingComplete()
        ).isEqualTo(
                expected.cost().pricingComplete()
        );
    }

    private Instant instant(
            String value
    ) {
        return Instant.parse(value);
    }
}