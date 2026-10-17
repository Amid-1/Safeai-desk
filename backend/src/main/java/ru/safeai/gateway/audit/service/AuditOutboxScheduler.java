// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/audit/service/AuditOutboxScheduler.java
package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A failure in the audit worker must remain visible, but must never leak
 * exception messages, SQL parameters or stack traces to application logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditOutboxScheduler {

    private final AuditOutboxProcessor processor;
    private final AuditOutboxMetrics metrics;

    @Scheduled(fixedDelayString = "${safeai.audit.outbox.poll-delay-ms:1000}")
    public void process() {
        try {
            AuditOutboxProcessor.BatchResult result = processor.processBatch();
            if (result.processed() > 0 || result.failed() > 0) {
                log.debug("Processed audit outbox batch: processed={}, failed={}, deadLettered={}",
                        result.processed(), result.failed(), result.deadLettered());
            }
            if (result.deadLettered() > 0) {
                log.error("Audit outbox delivery reached dead-letter: count={}", result.deadLettered());
            }
        } catch (RuntimeException failure) {
            try {
                metrics.recordProcessorFailure();
            } catch (RuntimeException metricsFailure) {
                log.warn("Audit outbox processor failure counter unavailable: failureType={}",
                        metricsFailure.getClass().getName());
            }
            // No Throwable argument: drivers may include PII, tokens or SQL binds in the exception.
            log.error("Unable to process audit outbox batch: failureType={}",
                    failure.getClass().getName());
        }
    }
}
