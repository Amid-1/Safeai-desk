package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageQueryContractTest {

    private static final Instant FROM =
            Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-01T00:00:00Z");

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
