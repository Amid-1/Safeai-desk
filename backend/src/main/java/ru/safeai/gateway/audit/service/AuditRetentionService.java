package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.config.AuditRetentionProperties;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRetentionService {

    private final AuditRetentionBatchService batchService;
    private final AuditRetentionProperties properties;
    private final Clock clock;

    @Scheduled(
            cron = "${safeai.audit.retention.cron:0 30 3 * * *}",
            zone = "${safeai.audit.retention.zone:UTC}"
    )
    public void cleanupExpiredAuditEvents() {
        if (!properties.isEnabled()) {
            return;
        }

        Instant threshold = clock.instant().minus(
                properties.effectivePeriod()
        );

        int totalDeleted = 0;
        int batchSize = properties.effectiveBatchSize();
        int maxBatches =
                properties.effectiveMaxBatchesPerRun();

        for (int batch = 0;
             batch < maxBatches;
             batch++) {

            int deleted = batchService.deleteBatch(
                    threshold,
                    batchSize
            );

            totalDeleted += deleted;

            if (deleted < batchSize) {
                break;
            }
        }

        if (totalDeleted > 0) {
            log.info(
                    "Audit retention cleanup completed: "
                            + "deleted={}, threshold={}",
                    totalDeleted,
                    threshold
            );
        }
    }
}