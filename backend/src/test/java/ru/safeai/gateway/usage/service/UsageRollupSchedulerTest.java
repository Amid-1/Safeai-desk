
package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsageDirtyDateRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageRollupSchedulerTest {

    private static final long LOCK_KEY = 8_026_001L;

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T12:00:00Z"),
            ZoneOffset.UTC
    );

    private static final LocalDate LATEST_CLOSED_DATE =
            LocalDate.of(2026, 8, 1);

    @Mock
    private UsageRollupStateRepository stateRepository;

    @Mock
    private UsageRollupDayProcessor dayProcessor;

    @Mock
    private UsageDirtyDateRepository dirtyDates;

    @Mock
    private UsageDirtyDateProcessor dirtyProcessor;

    private UsageRollupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = scheduler(3, 31);
    }

    @Test
    void backfillAdvancesContiguousDaysAndDoesNotRebuildThemTwice() {
        executeLockBody();

        when(stateRepository.findLastCompletedDate())
                .thenReturn(
                        LocalDate.of(2026, 7, 30),
                        LATEST_CLOSED_DATE
                );

        scheduler.rebuild();

        InOrder order = inOrder(dayProcessor);

        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 31),
                true
        );

        order.verify(dayProcessor).rebuildDay(
                LATEST_CLOSED_DATE,
                true
        );

        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 30),
                false
        );

        order.verifyNoMoreInteractions();

        verify(dirtyDates).findPending(
                LATEST_CLOSED_DATE,
                31
        );

        verifyNoInteractions(dirtyProcessor);
    }

    @Test
    void firstBackfillStartsAtEarliestUsageUtcDate() {
        UsageRollupScheduler limited = scheduler(2, 2);

        executeLockBody();

        when(stateRepository.findLastCompletedDate())
                .thenReturn(
                        null,
                        LocalDate.of(2026, 7, 29)
                );

        when(stateRepository.findEarliestUsageDate())
                .thenReturn(LocalDate.of(2026, 7, 28));

        limited.rebuild();

        InOrder order = inOrder(dayProcessor);

        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 28),
                true
        );

        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 29),
                true
        );

        order.verifyNoMoreInteractions();

        verify(dayProcessor, never()).rebuildDay(
                LocalDate.of(2026, 7, 30),
                true
        );

        verify(dayProcessor, never()).rebuildDay(
                LocalDate.of(2026, 7, 31),
                false
        );

        verify(dayProcessor, never()).rebuildDay(
                LATEST_CLOSED_DATE,
                false
        );

        verify(dirtyDates).findPending(
                LocalDate.of(2026, 7, 29),
                2
        );

        verifyNoInteractions(dirtyProcessor);
    }

    @Test
    void lockContentionSkipsEntireRun() {
        when(stateRepository.executeWithAdvisoryLock(
                eq(LOCK_KEY),
                any(Runnable.class)
        )).thenReturn(false);

        scheduler.rebuild();

        verify(stateRepository).executeWithAdvisoryLock(
                eq(LOCK_KEY),
                any(Runnable.class)
        );

        verify(stateRepository, never())
                .findLastCompletedDate();

        verifyNoInteractions(
                dayProcessor,
                dirtyDates,
                dirtyProcessor
        );
    }

    @Test
    void emptyHistoryInitializesWatermarkAndReconcilesRecentClosedDays() {
        executeLockBody();

        when(stateRepository.findLastCompletedDate())
                .thenReturn(
                        null,
                        LATEST_CLOSED_DATE
                );

        scheduler.rebuild();

        verify(stateRepository).findEarliestUsageDate();

        verify(stateRepository).markCompleted(
                LATEST_CLOSED_DATE
        );

        InOrder order = inOrder(dayProcessor);

        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 30),
                false
        );

        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 31),
                false
        );

        order.verify(dayProcessor).rebuildDay(
                LATEST_CLOSED_DATE,
                false
        );

        order.verifyNoMoreInteractions();

        verify(dirtyDates).findPending(
                LATEST_CLOSED_DATE,
                31
        );

        verifyNoInteractions(dirtyProcessor);
    }

    @Test
    void currentUtcDayOnlyHistoryStillInitializesClosedDayWatermark() {
        executeLockBody();

        when(stateRepository.findLastCompletedDate())
                .thenReturn(
                        null,
                        LATEST_CLOSED_DATE
                );

        when(stateRepository.findEarliestUsageDate())
                .thenReturn(LocalDate.of(2026, 8, 2));

        scheduler.rebuild();

        verify(stateRepository).markCompleted(
                LATEST_CLOSED_DATE
        );

        verify(dayProcessor, never()).rebuildDay(
                LocalDate.of(2026, 8, 2),
                true
        );

        verify(dayProcessor, never()).rebuildDay(
                LocalDate.of(2026, 8, 2),
                false
        );

        verify(dirtyDates).findPending(
                LATEST_CLOSED_DATE,
                31
        );

        verifyNoInteractions(dirtyProcessor);
    }

    @Test
    void processesDirtyDatesWithinWatermark() {
        executeLockBody();

        when(stateRepository.findLastCompletedDate())
                .thenReturn(LATEST_CLOSED_DATE);

        LocalDate dirtyDate = LocalDate.of(2026, 7, 15);

        UsageDirtyDateRepository.DirtyDate pending =
                new UsageDirtyDateRepository.DirtyDate(
                        dirtyDate,
                        3L
                );

        when(dirtyDates.findPending(
                LATEST_CLOSED_DATE,
                31
        )).thenReturn(List.of(pending));

        when(dirtyProcessor.rebuildIfPending(dirtyDate))
                .thenReturn(true);

        scheduler.rebuild();

        verify(dirtyDates).findPending(
                LATEST_CLOSED_DATE,
                31
        );

        verify(dirtyProcessor).rebuildIfPending(
                dirtyDate
        );

        verify(dayProcessor, never()).rebuildDay(
                dirtyDate,
                true
        );

        verify(dayProcessor, never()).rebuildDay(
                dirtyDate,
                false
        );
    }

    private UsageRollupScheduler scheduler(
            int lookbackDays,
            int backfillDaysPerRun
    ) {
        UsageProperties properties = new UsageProperties(
                Duration.ofDays(30),
                Duration.ofDays(366),
                Duration.ofDays(31),
                200,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                200,
                4,
                new UsageProperties.Rollup(
                        true,
                        "0 */10 * * * *",
                        lookbackDays,
                        backfillDaysPerRun,
                        LOCK_KEY,
                        Duration.ofMinutes(2)
                )
        );

        return new UsageRollupScheduler(
                properties,
                stateRepository,
                dayProcessor,
                dirtyDates,
                dirtyProcessor,
                CLOCK
        );
    }

    private void executeLockBody() {
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return true;
        }).when(stateRepository).executeWithAdvisoryLock(
                eq(LOCK_KEY),
                any(Runnable.class)
        );
    }
}
