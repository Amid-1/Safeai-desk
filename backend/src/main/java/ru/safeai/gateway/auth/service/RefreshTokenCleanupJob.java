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

    @Scheduled(cron = "${safeai.auth.refresh-cleanup.cron:0 0 3 * * *}", zone = "UTC")
    public void scheduledCleanup() {
        CleanupResult result = runCleanup();
        if (result.limitReached()) {
            log.warn("Refresh-token cleanup reached configured limit: "
                            + "deletedRows={}, committedBatches={}",
                    result.deletedRows(), result.committedBatches());
        } else if (result.deletedRows() > 0) {
            log.info("Refresh-token cleanup completed: "
                            + "deletedRows={}, committedBatches={}",
                    result.deletedRows(), result.committedBatches());
        } else {
            log.debug("Refresh-token cleanup completed: no eligible rows");
        }
    }

    /**
     * No outer transaction: each deleteNextBatch call uses REQUIRES_NEW.
     * A short batch is not necessarily the last: deleting a root may unlock
     * another eligible element of a token replacement chain after commit.
     */
    public CleanupResult runCleanup() {
        Instant threshold = Objects.requireNonNull(clock.instant(),
                "clock.instant() не должен быть null").minus(
                authCookieProperties.reuseDetectionRetention());
        return runCleanup(threshold, cleanupProperties.batchSize(),
                cleanupProperties.maxBatchesPerRun());
    }

    private CleanupResult runCleanup(Instant threshold, int batchSize, int maxBatches) {
        Objects.requireNonNull(threshold, "threshold не должен быть null");
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize должен быть в диапазоне 1..10000");
        }
        if (maxBatches < 1 || maxBatches > 10_000) {
            throw new IllegalArgumentException("maxBatches должен быть в диапазоне 1..10000");
        }

        long totalDeleted = 0L;
        int committedBatches = 0;

        while (committedBatches < maxBatches) {
            int deleted = batchService.deleteNextBatch(threshold, batchSize);
            if (deleted < 0 || deleted > batchSize) {
                throw new IllegalStateException(
                        "BatchService вернул некорректное количество удалённых строк: " + deleted);
            }
            if (deleted == 0) {
                return CleanupResult.completed(totalDeleted, committedBatches);
            }
            totalDeleted = Math.addExact(totalDeleted, deleted);
            committedBatches++;
        }
        return CleanupResult.limitReached(totalDeleted, committedBatches);
    }

    public record CleanupResult(long deletedRows, int committedBatches,
                                boolean limitReached) {
        public CleanupResult {
            if (deletedRows < 0) {
                throw new IllegalArgumentException("deletedRows не может быть отрицательным");
            }
            if (committedBatches < 0) {
                throw new IllegalArgumentException("committedBatches не может быть отрицательным");
            }
            if (deletedRows == 0 && committedBatches > 0) {
                throw new IllegalArgumentException(
                        "committedBatches не может быть положительным при deletedRows=0");
            }
            if (deletedRows > 0 && committedBatches == 0) {
                throw new IllegalArgumentException(
                        "deletedRows не может быть положительным при committedBatches=0");
            }
        }

        public static CleanupResult completed(long deletedRows, int committedBatches) {
            return new CleanupResult(deletedRows, committedBatches, false);
        }

        public static CleanupResult limitReached(long deletedRows, int committedBatches) {
            return new CleanupResult(deletedRows, committedBatches, true);
        }
    }
}
