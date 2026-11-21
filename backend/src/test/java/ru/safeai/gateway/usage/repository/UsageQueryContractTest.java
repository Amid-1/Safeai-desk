package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageQueryContractTest {

    private static final Instant FROM =
            Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void userScopedModelAndDailyFactsUseUserRollupsAndLiveTenantIntersection() {
        UUID organization = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        var criteria = new UsageQueryCriteria(FROM, TO, organization, user, "model-a");
        var plan = new UsageQueryPlan(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 20),
                List.of(new UsageInstantRange(Instant.parse("2026-06-20T00:00:00Z"), TO)));
        for (boolean includeDate : List.of(false, true)) {
            var facts = new UsageFactQueryBuilder().organizationFacts(criteria, plan, includeDate);
            assertThat(facts.cte())
                    .contains("usage_daily_user_model_rollups", "r.organization_id = :organizationId",
                            "r.user_id = :userId", "m.organization_id = :organizationId",
                            "s.user_id = :userId", "join chat_sessions s", "r.model = :model", "m.model = :model")
                    .doesNotContain("usage_daily_org_model_rollups");
            assertThat(facts.parameters().getValue("userId")).isEqualTo(user);
            assertThat(facts.parameters().getValue("organizationId")).isEqualTo(organization);
            if (includeDate) assertThat(facts.cte()).contains("r.usage_date", "as usage_date");
        }
    }

    @Test
    void criteriaNormalizeModelAndRequireHalfOpenRange() {
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                FROM,
                TO,
                null,
                null,
                "  model-a  "
        );

        assertThat(criteria.model()).isEqualTo("model-a");

        assertThatThrownBy(() -> new UsageQueryCriteria(
                TO,
                TO,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFrom");
    }

    @Test
    void planRequiresBothRollupBoundsAndCopiesLiveRanges() {
        List<UsageInstantRange> mutable = new ArrayList<>(
                List.of(new UsageInstantRange(FROM, TO))
        );
        UsageQueryPlan plan = new UsageQueryPlan(
                null,
                null,
                mutable
        );
        mutable.clear();

        assertThat(plan.liveRanges()).hasSize(1);
        assertThatThrownBy(() -> plan.liveRanges().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new UsageQueryPlan(
                LocalDate.of(2026, 6, 1),
                null,
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Обе границы");
    }

    @Test
    void everyRollupFactExcludesDirtyDatesUsingBoundParameters() {
        var start = LocalDate.of(2026, 6, 1);
        var end = LocalDate.of(2026, 7, 1);
        var dirty = start.plusDays(2);
        var plan = UsageQueryPlan.hybrid(FROM, TO, start, end, List.of(dirty));
        var criteria = new UsageQueryCriteria(FROM, TO, UUID.randomUUID(), null, null);
        var builder = new UsageFactQueryBuilder();
        for (var facts : List.of(builder.userFacts(criteria, plan),
                builder.organizationFacts(criteria, plan, false),
                builder.organizationFacts(criteria, plan, true, true),
                builder.qualityFacts(criteria, plan))) {
            assertThat(facts.cte()).contains("r.usage_date not in (:excludedRollupDates)", "union all");
            assertThat(facts.parameters().getValue("excludedRollupDates")).isEqualTo(List.of(dirty));
        }
        assertThat(plan.liveRanges()).containsExactly(new UsageInstantRange(
                Instant.parse("2026-06-03T00:00:00Z"), Instant.parse("2026-06-04T00:00:00Z")));
    }

    @Test
    void plansRejectDuplicateDirtyDatesAndDoubleCounting() {
        var start = LocalDate.of(2026, 6, 1);
        var end = LocalDate.of(2026, 7, 1);
        assertThatThrownBy(() -> UsageQueryPlan.hybrid(FROM, TO, start, end, List.of(start, start)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageQueryPlan.hybrid(FROM, TO, start, end, List.of(end)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageQueryPlan(start, end, List.of(), List.of(start)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageQueryPlan(start, end, List.of(new UsageInstantRange(FROM, TO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageQueryPlan(null, null,
                List.of(new UsageInstantRange(FROM, TO), new UsageInstantRange(FROM, TO))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hybridPreservesCleanDaysAndRejectsRollupOutsideRequest() {
        var start = LocalDate.of(2026, 6, 1);
        var end = LocalDate.of(2026, 7, 1);
        List<LocalDate> dirty = new ArrayList<>(List.of(start.plusDays(4), start.plusDays(1)));
        var plan = UsageQueryPlan.hybrid(FROM, TO, start, end, dirty);
        dirty.clear();
        assertThat(plan.excludedRollupDates()).containsExactly(start.plusDays(1), start.plusDays(4));
        assertThat(plan.liveRanges()).containsExactly(
                new UsageInstantRange(Instant.parse("2026-06-02T00:00:00Z"),
                        Instant.parse("2026-06-03T00:00:00Z")),
                new UsageInstantRange(Instant.parse("2026-06-05T00:00:00Z"),
                        Instant.parse("2026-06-06T00:00:00Z")));
        assertThatThrownBy(() -> UsageQueryPlan.hybrid(FROM.plusSeconds(1), TO, start, end, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageQueryPlan.hybrid(FROM, TO.minusSeconds(1), start, end, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageQueryPlan.hybrid(FROM, TO, start, start, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instantRangeRejectsEmptyOrReversedIntervals() {
        assertThatThrownBy(() -> new UsageInstantRange(FROM, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("раньше");
        assertThatThrownBy(() -> new UsageInstantRange(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("раньше");
    }
}
