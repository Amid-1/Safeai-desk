package ru.safeai.gateway.usage.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

@Component
@ConditionalOnProperty(
        name = "safeai.usage.rollup.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class UsageRollupScheduler {

    private static final System.Logger LOGGER =
            System.getLogger(UsageRollupScheduler.class.getName());

    private final UsageProperties properties;
    private final UsageRollupStateRepository stateRepository;
    private final UsageRollupDayProcessor dayProcessor;
    private final Clock clock;

    public UsageRollupScheduler(
            UsageProperties properties,
            UsageRollupStateRepository stateRepository,
            UsageRollupDayProcessor dayProcessor,
            Clock clock
    ) {
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.dayProcessor = dayProcessor;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${safeai.usage.rollup.cron:0 */10 * * * *}",
            zone = "UTC"
    )
    public void rebuild() {
        long lockKey = properties.rollup().advisoryLockKey();

        boolean executed = stateRepository.executeWithAdvisoryLock(
                lockKey,
                () -> {
                    LocalDate today = LocalDate.now(
                            clock.withZone(ZoneOffset.UTC)
                    );
                    LocalDate latestClosedDate = today.minusDays(1);

                    Set<LocalDate> rebuiltDates =
                            backfill(latestClosedDate);

                    reconcileLookback(
                            latestClosedDate,
                            rebuiltDates
                    );
                }
        );

        if (!executed) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Usage rollup skipped: lock is held by another instance"
            );
        }
    }

    /**
     * Возвращает даты, реально пересчитанные в backfill текущего запуска.
     * Они затем исключаются из lookback reconciliation, чтобы один и тот же
     * UTC-день не агрегировался дважды подряд.
     */
    private Set<LocalDate> backfill(
            LocalDate latestClosedDate
    ) {
        LocalDate lastCompleted =
                stateRepository.findLastCompletedDate();
        LocalDate nextDate = lastCompleted == null
                ? stateRepository.findEarliestAssistantDate()
                : lastCompleted.plusDays(1);

        if (nextDate == null || nextDate.isAfter(latestClosedDate)) {
            // No assistant rows exist in any closed UTC day. Mark all closed
            // days as covered so an empty/new installation can answer long
            // reports from rollups instead of returning 503 forever. Current
            // UTC-day rows remain live and are not covered by this watermark.
            stateRepository.markCompleted(latestClosedDate);
            return Set.of();
        }

        int processed = 0;
        int limit = properties.rollup().backfillDaysPerRun();
        Set<LocalDate> rebuiltDates = new HashSet<>();

        while (!nextDate.isAfter(latestClosedDate)
                && processed < limit) {
            dayProcessor.rebuildDay(nextDate, true);
            rebuiltDates.add(nextDate);
            nextDate = nextDate.plusDays(1);
            processed++;
        }

        LOGGER.log(
                System.Logger.Level.INFO,
                "Usage rollup backfill processed {0} day(s)",
                processed
        );

        return Set.copyOf(rebuiltDates);
    }

    private void reconcileLookback(
            LocalDate latestClosedDate,
            Set<LocalDate> alreadyRebuilt
    ) {
        int lookbackDays = properties.rollup().lookbackDays();
        LocalDate firstDate = latestClosedDate
                .minusDays(lookbackDays - 1L);

        int processed = 0;

        for (LocalDate date = firstDate;
             !date.isAfter(latestClosedDate);
             date = date.plusDays(1)) {
            if (alreadyRebuilt.contains(date)) {
                continue;
            }

            dayProcessor.rebuildDay(date, false);
            processed++;
        }

        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Usage rollup reconciled UTC dates {0}..{1}; "
                        + "processed={2}, skippedAsBackfilled={3}",
                firstDate,
                latestClosedDate,
                processed,
                alreadyRebuilt.size()
        );
    }
}
