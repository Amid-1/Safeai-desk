package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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

    @Mock
    private UsageRollupStateRepository stateRepository;

    @Mock
    private UsageRollupDayProcessor dayProcessor;

    private UsageRollupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = scheduler(3, 31);
    }

    @Test
    void backfillAdvancesContiguousDaysAndDoesNotRebuildThemTwice() {
        executeLockBody();
        when(stateRepository.findLastCompletedDate())
                .thenReturn(LocalDate.of(2026, 7, 30));

        scheduler.rebuild();

        InOrder order = inOrder(dayProcessor);
        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 31),
                true
        );
        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 8, 1),
                true
        );
        order.verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 30),
                false
        );
        order.verifyNoMoreInteractions();
    }

    @Test
    void firstBackfillStartsAtEarliestAssistantUtcDate() {
        UsageRollupScheduler limited = scheduler(2, 2);
        executeLockBody();
        when(stateRepository.findLastCompletedDate()).thenReturn(null);
        when(stateRepository.findEarliestAssistantDate())
                .thenReturn(LocalDate.of(2026, 7, 28));

        limited.rebuild();

        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 28),
                true
        );
        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 29),
                true
        );
        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 31),
                false
        );
        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 8, 1),
                false
        );
        verify(dayProcessor, never()).rebuildDay(
                LocalDate.of(2026, 7, 30),
                true
        );
    }

    @Test
    void lockContentionSkipsEntireRun() {
        when(stateRepository.executeWithAdvisoryLock(
                eq(LOCK_KEY),
                any(Runnable.class)
        )).thenReturn(false);

        scheduler.rebuild();

        verifyNoInteractions(dayProcessor);
        verify(stateRepository, never()).findLastCompletedDate();
    }

    @Test
    void emptyHistoryInitializesWatermarkAndReconcilesRecentClosedDays() {
        executeLockBody();
        when(stateRepository.findLastCompletedDate()).thenReturn(null);
        when(stateRepository.findEarliestAssistantDate()).thenReturn(null);

        scheduler.rebuild();

        verify(stateRepository).markCompleted(
                LocalDate.of(2026, 8, 1)
        );
        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 30),
                false
        );
        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 7, 31),
                false
        );
        verify(dayProcessor).rebuildDay(
                LocalDate.of(2026, 8, 1),
                false
        );
    }

    @Test
    void currentUtcDayOnlyHistoryStillInitializesClosedDayWatermark() {
        executeLockBody();
        when(stateRepository.findLastCompletedDate()).thenReturn(null);
        when(stateRepository.findEarliestAssistantDate())
                .thenReturn(LocalDate.of(2026, 8, 2));

        scheduler.rebuild();

        verify(stateRepository).markCompleted(
                LocalDate.of(2026, 8, 1)
        );
        verify(dayProcessor, never()).rebuildDay(
                LocalDate.of(2026, 8, 2),
                true
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
