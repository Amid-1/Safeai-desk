package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenCleanupBatchService batchService;
    private final AuthCookieProperties authCookieProperties;
    private final RefreshTokenCleanupProperties cleanupProperties;
    private final Clock clock;

    @Scheduled(
            cron = "${safeai.auth.refresh-cleanup.cron:0 0 3 * * *}",
            zone = "UTC"
    )
    public void scheduledCleanup() {
        CleanupResult result = runCleanup();

        if (result.limitReached()) {
            log.warn(
                    "Refresh-token cleanup reached configured limit: "
                            + "deletedRows={}, committedBatches={}",
                    result.deletedRows(),
                    result.committedBatches()
            );

            return;
        }

        if (result.deletedRows() > 0) {
            log.info(
                    "Refresh-token cleanup completed: "
                            + "deletedRows={}, committedBatches={}",
                    result.deletedRows(),
                    result.committedBatches()
            );
        } else {
            log.debug(
                    "Refresh-token cleanup completed: no eligible rows"
            );
        }
    }

    /**
     * Доступен для integration tests и ручного запуска.
     *
     * <p>Внешней транзакции намеренно нет. Каждый batch выполняется
     * внутри отдельной REQUIRES_NEW transaction.</p>
     */
    public CleanupResult runCleanup() {
        Instant threshold = clock.instant().minus(
                authCookieProperties.reuseDetectionRetention()
        );

        return runCleanup(
                threshold,
                cleanupProperties.batchSize(),
                cleanupProperties.maxBatchesPerRun()
        );
    }

    private CleanupResult runCleanup(
            Instant threshold,
            int batchSize,
            int maxBatches
    ) {
        Objects.requireNonNull(
                threshold,
                "threshold не должен быть null"
        );

        long totalDeleted = 0L;
        int committedBatches = 0;

        while (committedBatches < maxBatches) {
            int deleted = batchService.deleteNextBatch(
                    threshold,
                    batchSize
            );

            if (deleted == 0) {
                return CleanupResult.completed(
                        totalDeleted,
                        committedBatches
                );
            }

            totalDeleted = Math.addExact(
                    totalDeleted,
                    deleted
            );

            committedBatches++;

            /*
             * Нельзя завершать run только потому, что deleted < batchSize.
             *
             * После FK-safe root-only delete следующий элемент той же
             * replacement chain становится eligible только ПОСЛЕ commit
             * текущего REQUIRES_NEW batch. Поэтому продолжаем до:
             *
             * 1) batch с deleted == 0; либо
             * 2) maxBatchesPerRun.
             *
             * При нескольких workers SKIP LOCKED по-прежнему позволяет
             * безопасно делить независимые roots между instances.
             */
        }

        return CleanupResult.limitReached(
                totalDeleted,
                committedBatches
        );
    }

    public record CleanupResult(
            long deletedRows,
            int committedBatches,
            boolean limitReached
    ) {

        public CleanupResult {
            if (deletedRows < 0) {
                throw new IllegalArgumentException(
                        "deletedRows не может быть отрицательным"
                );
            }

            if (committedBatches < 0) {
                throw new IllegalArgumentException(
                        "committedBatches не может быть отрицательным"
                );
            }

            if (deletedRows == 0 && committedBatches > 0) {
                throw new IllegalArgumentException(
                        "committedBatches не может быть положительным "
                                + "при deletedRows=0"
                );
            }
        }

        public static CleanupResult completed(
                long deletedRows,
                int committedBatches
        ) {
            return new CleanupResult(
                    deletedRows,
                    committedBatches,
                    false
            );
        }

        public static CleanupResult limitReached(
                long deletedRows,
                int committedBatches
        ) {
            return new CleanupResult(
                    deletedRows,
                    committedBatches,
                    true
            );
        }
    }
}
