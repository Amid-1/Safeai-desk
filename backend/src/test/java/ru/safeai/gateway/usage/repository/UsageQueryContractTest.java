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
    void instantRangeRejectsEmptyOrReversedIntervals() {
        assertThatThrownBy(() -> new UsageInstantRange(FROM, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("раньше");
        assertThatThrownBy(() -> new UsageInstantRange(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("раньше");
    }
}
