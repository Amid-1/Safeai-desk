package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(
        properties =
                "safeai.usage.rollup.enabled=false"
)
@ActiveProfiles("test")
class UsageUtcAggregationIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    /*
     * PostgreSQL timestamptz имеет микросекундную точность.
     * Поэтому для проверки границ нельзя использовать разницу в 1 нс:
     * значение может округлиться до соседней секунды.
     */
    private static final long POSTGRES_MICROSECOND_NANOS =
            1_000L;

    private static final String MODEL =
            "utc-model";

    @Autowired
    private UsageQueryRepository repository;

    @Test
    void dateFromIsInclusiveAndDateToIsExclusive() {
        Instant from =
                Instant.parse(
                        "2026-06-10T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-06-11T00:00:00Z"
                );

        message(
                from.minusNanos(
                        POSTGRES_MICROSECOND_NANOS
                ),
                100,
                100
        );

        message(
                from,
                10,
                20
        );

        message(
                to.minusNanos(
                        POSTGRES_MICROSECOND_NANOS
                ),
                30,
                40
        );

        message(
                to,
                200,
                200
        );

        UsageModelSummaryResponse result =
                repository.findModels(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                MODEL,
                                from,
                                to
                        ),
                        livePlan(
                                from,
                                to
                        )
                ).getFirst();

        assertThat(
                result.responses()
                        .assistantMessages()
        ).isEqualTo(2);

        assertThat(
                result.usage()
                        .confirmedInputTokens()
        ).isEqualTo(40);

        assertThat(
                result.usage()
                        .confirmedOutputTokens()
        ).isEqualTo(60);
    }

    @Test
    void dailyReportUsesUtcCalendarBoundary() {
        Instant from =
                Instant.parse(
                        "2026-06-12T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-06-14T00:00:00Z"
                );

        message(
                Instant.parse(
                        "2026-06-12T23:59:59.999999Z"
                ),
                10,
                20
        );

        message(
                Instant.parse(
                        "2026-06-13T00:00:00Z"
                ),
                30,
                40
        );

        List<UsageDailySummaryResponse> result =
                repository.findDaily(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                null,
                                from,
                                to
                        ),
                        livePlan(
                                from,
                                to
                        )
                );

        assertThat(result)
                .extracting(
                        UsageDailySummaryResponse::usageDate
                )
                .containsExactly(
                        LocalDate.of(
                                2026,
                                6,
                                13
                        ),
                        LocalDate.of(
                                2026,
                                6,
                                12
                        )
                );

        assertThat(result)
                .extracting(
                        UsageDailySummaryResponse::aggregationZone
                )
                .containsOnly("UTC");
    }

    @Test
    void daylightSavingTransitionsDoNotChangeUtcGrouping() {
        Instant from =
                Instant.parse(
                        "2026-03-28T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-03-30T00:00:00Z"
                );

        /*
         * Europe/Helsinki switches to daylight saving time on this
         * weekend, but the API contract groups immutable instants
         * by UTC date only.
         */
        message(
                Instant.parse(
                        "2026-03-28T23:30:00Z"
                ),
                10,
                10
        );

        message(
                Instant.parse(
                        "2026-03-29T00:30:00Z"
                ),
                20,
                20
        );

        message(
                Instant.parse(
                        "2026-03-29T23:30:00Z"
                ),
                30,
                30
        );

        List<UsageDailySummaryResponse> result =
                repository.findDaily(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                null,
                                from,
                                to
                        ),
                        livePlan(
                                from,
                                to
                        )
                );

        assertThat(result)
                .extracting(
                        UsageDailySummaryResponse::usageDate
                )
                .containsExactly(
                        LocalDate.of(
                                2026,
                                3,
                                29
                        ),
                        LocalDate.of(
                                2026,
                                3,
                                28
                        )
                );

        UsageDailySummaryResponse march29 =
                result.getFirst();

        assertThat(
                march29.responses()
                        .assistantMessages()
        ).isEqualTo(2);

        assertThat(
                march29.usage()
                        .confirmedTotalTokens()
        ).isEqualTo(100);
    }

    private void message(
            Instant createdAt,
            int inputTokens,
            int outputTokens
    ) {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                inputTokens,
                outputTokens,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE",
                createdAt
        );
    }
}