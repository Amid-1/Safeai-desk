package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditOutboxScheduler {

    private final AuditOutboxProcessor auditOutboxProcessor;

    @Scheduled(
            fixedDelayString =
                    "${safeai.audit.outbox.poll-delay-ms:1000}"
    )
    public void process() {
        try {
            int processed = auditOutboxProcessor.processBatch();

            if (processed > 0) {
                log.debug(
                        "Processed audit outbox batch: count={}",
                        processed
                );
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Unable to process audit outbox batch",
                    exception
            );
        }
    }
}
