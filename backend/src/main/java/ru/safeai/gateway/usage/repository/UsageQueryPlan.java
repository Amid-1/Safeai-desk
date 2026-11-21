package ru.safeai.gateway.usage.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record UsageQueryPlan(
        LocalDate rollupFrom,
        LocalDate rollupToExclusive,
        List<UsageInstantRange> liveRanges,
        List<LocalDate> excludedRollupDates
) {
    public UsageQueryPlan(LocalDate rollupFrom, LocalDate rollupToExclusive, List<UsageInstantRange> liveRanges) {
        this(rollupFrom, rollupToExclusive, liveRanges, List.of());
    }

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
        excludedRollupDates = List.copyOf(excludedRollupDates);
        if (new HashSet<>(excludedRollupDates).size() != excludedRollupDates.size()) {
            throw new IllegalArgumentException("Duplicate dirty rollup date");
        }
        for (LocalDate date : excludedRollupDates) {
            if (rollupFrom == null || date.isBefore(rollupFrom) || !date.isBefore(rollupToExclusive)
                    || liveRanges.stream().noneMatch(range -> !range.from().isAfter(midnight(date))
                    && !range.to().isBefore(midnight(date.plusDays(1))))) {
                throw new IllegalArgumentException("Excluded rollup date must be covered by live facts");
            }
        }
        validateDisjointCoverage(rollupFrom, rollupToExclusive, liveRanges, excludedRollupDates.size());
    }

    private static void validateDisjointCoverage(LocalDate from, LocalDate to,
                                                  List<UsageInstantRange> live, int excludedCount) {
        Instant previousEnd = null;
        Duration overlap = Duration.ZERO;
        for (UsageInstantRange range : live.stream().sorted(Comparator.comparing(UsageInstantRange::from)).toList()) {
            if (previousEnd != null && range.from().isBefore(previousEnd)) {
                throw new IllegalArgumentException("Live ranges overlap");
            }
            previousEnd = range.to();
            if (from != null) {
                Instant start = range.from().isAfter(midnight(from)) ? range.from() : midnight(from);
                Instant end = range.to().isBefore(midnight(to)) ? range.to() : midnight(to);
                if (start.isBefore(end)) overlap = overlap.plus(Duration.between(start, end));
            }
        }
        if (!overlap.equals(Duration.ofDays(excludedCount))) {
            throw new IllegalArgumentException("Live and clean rollup ranges overlap");
        }
    }

    /** Build disjoint live ranges, replacing whole dirty UTC days, never adding them twice. */
    public static UsageQueryPlan hybrid(Instant from, Instant to, LocalDate rollupFrom,
                                        LocalDate rollupToExclusive, List<LocalDate> dirtyDates) {
        Instant start = midnight(rollupFrom);
        Instant end = midnight(rollupToExclusive);
        if (from.isAfter(start) || end.isAfter(to) || !start.isBefore(end)) {
            throw new IllegalArgumentException("Rollup must be inside requested range");
        }
        List<LocalDate> sorted = dirtyDates.stream().sorted().toList();
        List<UsageInstantRange> live = new ArrayList<>();
        append(live, from, start);
        for (LocalDate date : sorted) {
            if (date.isBefore(rollupFrom) || !date.isBefore(rollupToExclusive)) {
                throw new IllegalArgumentException("Dirty date outside rollup");
            }
            append(live, midnight(date), midnight(date.plusDays(1)));
        }
        append(live, end, to);
        return new UsageQueryPlan(rollupFrom, rollupToExclusive, live, sorted);
    }

    private static Instant midnight(LocalDate date) {
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static void append(List<UsageInstantRange> live, Instant from, Instant to) {
        if (!from.isBefore(to)) return;
        if (!live.isEmpty() && live.getLast().to().equals(from)) {
            live.set(live.size() - 1, new UsageInstantRange(live.getLast().from(), to));
        } else {
            live.add(new UsageInstantRange(from, to));
        }
    }

    public boolean hasRollupRange() {
        return rollupFrom != null;
    }
}
