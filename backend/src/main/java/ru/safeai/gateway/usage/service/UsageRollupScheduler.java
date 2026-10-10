package ru.safeai.gateway.usage.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsageDirtyDateRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "safeai.usage.rollup.enabled", havingValue = "true", matchIfMissing = true)
public class UsageRollupScheduler {
    private static final System.Logger LOGGER =
            System.getLogger(UsageRollupScheduler.class.getName());

    private final UsageProperties properties;
    private final UsageRollupStateRepository stateRepository;
    private final UsageRollupDayProcessor dayProcessor;
    private final UsageDirtyDateRepository dirtyDates;
    private final UsageDirtyDateProcessor dirtyProcessor;
    private final Clock clock;

    public UsageRollupScheduler(
            UsageProperties properties,
            UsageRollupStateRepository stateRepository,
            UsageRollupDayProcessor dayProcessor,
            UsageDirtyDateRepository dirtyDates,
            UsageDirtyDateProcessor dirtyProcessor,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.dayProcessor = Objects.requireNonNull(dayProcessor, "dayProcessor");
        this.dirtyDates = Objects.requireNonNull(dirtyDates, "dirtyDates");
        this.dirtyProcessor = Objects.requireNonNull(dirtyProcessor, "dirtyProcessor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(cron = "${safeai.usage.rollup.cron:0 */10 * * * *}", zone = "UTC")
    public void rebuild() {
        boolean acquired = stateRepository.executeWithAdvisoryLock(
                properties.rollup().advisoryLockKey(),
                () -> {
                    LocalDate latestClosedDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                            .minusDays(1);
                    Set<LocalDate> backfilled = backfill(latestClosedDate);
                    LocalDate watermark = stateRepository.findLastCompletedDate();
                    if (watermark == null) {
                        return;
                    }
                    LocalDate coveredThrough = watermark.isBefore(latestClosedDate)
                            ? watermark : latestClosedDate;
                    reconcileLookback(coveredThrough, backfilled);
                    rebuildDirtyDates(coveredThrough);
                });
        if (!acquired) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Usage rollup skipped: lock held by another instance");
        }
    }

    private Set<LocalDate> backfill(LocalDate latestClosedDate) {
        LocalDate lastCompleted = stateRepository.findLastCompletedDate();
        LocalDate nextDate = lastCompleted == null
                ? stateRepository.findEarliestUsageDate()
                : lastCompleted.plusDays(1);

        if (lastCompleted == null && (nextDate == null || nextDate.isAfter(latestClosedDate))) {
            // New empty installation: no source messages in any closed UTC day.
            stateRepository.markCompleted(latestClosedDate);
            return Set.of();
        }
        if (nextDate == null) {
            return Set.of();
        }
        if (nextDate.isAfter(latestClosedDate)) {
            return Set.of();
        }

        int limit = properties.rollup().backfillDaysPerRun();
        Set<LocalDate> rebuilt = new HashSet<>();
        while (!nextDate.isAfter(latestClosedDate) && rebuilt.size() < limit) {
            dayProcessor.rebuildDay(nextDate, true);
            rebuilt.add(nextDate);
            nextDate = nextDate.plusDays(1);
        }
        return Set.copyOf(rebuilt);
    }

    private void reconcileLookback(LocalDate coveredThrough, Set<LocalDate> alreadyRebuilt) {
        LocalDate firstDate = coveredThrough.minusDays(properties.rollup().lookbackDays() - 1L);
        for (LocalDate date = firstDate; !date.isAfter(coveredThrough); date = date.plusDays(1)) {
            if (!alreadyRebuilt.contains(date)) {
                dayProcessor.rebuildDay(date, false);
            }
        }
    }

    private void rebuildDirtyDates(LocalDate coveredThrough) {
        int batchLimit = properties.rollup().backfillDaysPerRun();
        int processed = 0;
        var pendingDates = Objects.requireNonNull(
                dirtyDates.findPending(coveredThrough, batchLimit),
                "findPending must not return null"
        );
        for (UsageDirtyDateRepository.DirtyDate item : pendingDates) {
            // Each call uses its own write transaction. Failure leaves dirty row for retry.
            if (dirtyProcessor.rebuildIfPending(item.date())) {
                processed++;
            }
        }
        if (processed > 0) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Usage dirty-date rebuild processed {0} day(s)", processed);
        }
    }
}
