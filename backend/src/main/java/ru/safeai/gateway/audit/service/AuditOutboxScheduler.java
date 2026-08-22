package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditOutboxScheduler {

    private final AuditOutboxProcessor processor;
    private final AuditOutboxMetrics metrics;

    @Scheduled(
            fixedDelayString =
                    "${safeai.audit.outbox.poll-delay-ms:1000}"
    )
    public void process() {
        try {
            AuditOutboxProcessor.BatchResult result =
                    processor.processBatch();

            if (result.processed() > 0
                    || result.failed() > 0) {
                log.debug(
                        "Processed audit outbox batch: "
                                + "processed={}, failed={}, "
                                + "deadLettered={}",
                        result.processed(),
                        result.failed(),
                        result.deadLettered()
                );
            }

            if (result.deadLettered() > 0) {
                log.error(
                        "Audit outbox rows moved to dead-letter: count={}",
                        result.deadLettered()
                );
            }
        } catch (RuntimeException exception) {
            metrics.recordProcessorFailure();

            log.error(
                    "Unable to process audit outbox batch",
                    exception
            );
        }
    }
}
