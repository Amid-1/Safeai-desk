package ru.safeai.gateway.usage.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record UsageQueryPlan(
        LocalDate rollupFrom,
        LocalDate rollupToExclusive,
        List<UsageInstantRange> liveRanges
) {
    public UsageQueryPlan {
        liveRanges = List.copyOf(
                Objects.requireNonNull(
                        liveRanges,
                        "liveRanges не должен быть null"
                )
        );

        if ((rollupFrom == null) != (rollupToExclusive == null)) {
            throw new IllegalArgumentException(
                    "Обе границы rollup должны быть заданы вместе"
            );
        }

        if (rollupFrom != null
                && !rollupFrom.isBefore(rollupToExclusive)) {
            throw new IllegalArgumentException(
                    "rollupFrom должен быть раньше rollupToExclusive"
            );
        }
    }

    public boolean hasRollupRange() {
        return rollupFrom != null;
    }
}
