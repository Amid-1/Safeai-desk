package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.config.AuditRetentionProperties;

import java.time.Duration;

/**
 * Retention remains externally callable with the original fail-fast contract.
 * Only the scheduler entry point masks untrusted exception detail in logs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRetentionService {

    private final AuditRetentionBatchService batchService;
    private final AuditRetentionLockService lockService;
    private final AuditRetentionProperties properties;

    @Scheduled(
            cron = "${safeai.audit.retention.cron:0 30 3 * * *}",
            zone = "${safeai.audit.retention.zone:UTC}"
    )
    public void scheduledCleanupExpiredAuditEvents() {
        try {
            cleanupExpiredAuditEvents();
        } catch (RuntimeException failure) {
            // Do not hand an exception (or its cause) to Spring's task logger.
            log.error("Audit retention scheduled execution failed: failureType={}",
                    failure.getClass().getName());
        }
    }

    /** Existing callable API: exceptions propagate to tests, CLI and operators. */
    public void cleanupExpiredAuditEvents() {
        if (!properties.isEnabled()) {
            return;
        }

        Duration retentionPeriod = properties.effectivePeriod();
        AuditRetentionLockService.LockExecution<Integer> execution =
                lockService.tryExecute(() -> deleteBatches(retentionPeriod));

        if (!execution.acquired()) {
            log.debug("Audit retention skipped: another backend instance holds the lock");
            return;
        }

        int totalDeleted = execution.result();
        if (totalDeleted > 0) {
            log.info("Audit retention cleanup completed: deleted={}, retentionPeriod={}",
                    totalDeleted, retentionPeriod);
        }
    }

    private int deleteBatches(Duration retentionPeriod) {
        int totalDeleted = 0;
        int batchSize = properties.effectiveBatchSize();
        int maxBatches = properties.effectiveMaxBatchesPerRun();

        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = batchService.deleteBatch(retentionPeriod, batchSize);
            totalDeleted = Math.addExact(totalDeleted, deleted);
            if (deleted < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }
}
