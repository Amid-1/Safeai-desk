// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/audit/service/AuditOutboxMetrics.java
package ru.safeai.gateway.audit.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics describe persisted PostgreSQL state, so they work across replicas.
 * Prometheus must use max(), NOT sum(), over replicas for backlog gauges.
 * See monitoring/alerts and RUNBOOK for mandatory production alerting.
 */
@Slf4j
@Component
public class AuditOutboxMetrics {

    private static final long BACKLOG_REFRESH_DELAY_MILLIS = 15_000L;
    private static final Duration DEAD_LETTER_LOG_INTERVAL = Duration.ofMinutes(15);

    private final AuditOutboxRepository repository;
    private final Clock clock;

    private final AtomicLong pendingRows = new AtomicLong();
    private final AtomicLong deadLetterRows = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    private final Counter deadLetterTransitions;
    private final Counter deliveryFailures;
    private final Counter processorFailures;
    private final Counter metricsRefreshFailures;
    private Instant lastDeadLetterLogAt;

    public AuditOutboxMetrics(
            AuditOutboxRepository repository,
            MeterRegistry registry,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository не должен быть null");
        Objects.requireNonNull(registry, "registry не должен быть null");
        this.clock = Objects.requireNonNull(clock, "clock не должен быть null");

        Gauge.builder("safeai.audit.outbox.pending", pendingRows, AtomicLong::get)
                .description("Current number of non-dead-letter audit outbox rows")
                .register(registry);
        Gauge.builder("safeai.audit.outbox.dead.letter.rows", deadLetterRows, AtomicLong::get)
                .description("Current number of audit outbox dead-letter rows")
                .register(registry);
        Gauge.builder("safeai.audit.outbox.oldest.age", oldestPendingAgeSeconds, AtomicLong::get)
                .description("Age of the oldest pending audit outbox row")
                .baseUnit("seconds")
                .register(registry);

        deadLetterTransitions = Counter.builder("safeai.audit.outbox.dead.letter")
                .description("Audit outbox rows moved to dead-letter").register(registry);
        deliveryFailures = Counter.builder("safeai.audit.outbox.delivery.failures")
                .description("Audit outbox delivery failures").register(registry);
        processorFailures = Counter.builder("safeai.audit.outbox.processor.failures")
                .description("Audit outbox batch processor failures").register(registry);
        metricsRefreshFailures = Counter.builder("safeai.audit.outbox.metrics.refresh.failures")
                .description("Failures while refreshing audit outbox backlog metrics")
                .register(registry);
    }

    public void recordDeliveryFailure(boolean deadLettered) {
        deliveryFailures.increment();
        if (deadLettered) {
            deadLetterTransitions.increment();
        }
    }

    public void recordProcessorFailure() {
        processorFailures.increment();
    }

    /**
     * Runs even when the outbox processor is stuck; failures keep previous
     * gauges intact and increment a separate counter for alerting.
     */
    @Scheduled(fixedDelay = BACKLOG_REFRESH_DELAY_MILLIS)
    public void refreshBacklog() {
        try {
            long pending = repository.countByDeadLetteredAtIsNull();
            long deadLettered = repository.countByDeadLetteredAtIsNotNull();
            long oldestAge = repository.findOldestPendingCreatedAt()
                    .map(this::ageSeconds).orElse(0L);

            pendingRows.set(pending);
            deadLetterRows.set(deadLettered);
            oldestPendingAgeSeconds.set(oldestAge);

            Instant now = clock.instant();
            if (deadLettered > 0L && (lastDeadLetterLogAt == null
                    || !now.isBefore(lastDeadLetterLogAt.plus(DEAD_LETTER_LOG_INTERVAL)))) {
                log.error("AUDIT DEAD-LETTER REQUIRES OPERATOR ACTION: rows={}", deadLettered);
                lastDeadLetterLogAt = now;
            }
            if (deadLettered == 0L) {
                lastDeadLetterLogAt = null;
            }
        } catch (RuntimeException failure) {
            try {
                metricsRefreshFailures.increment();
            } catch (RuntimeException metricFailure) {
                log.warn("Audit outbox metrics failure counter unavailable: failureType={}",
                        metricFailure.getClass().getName());
            }
            // Never log Throwable: database exception text is untrusted.
            log.warn("Unable to refresh audit outbox backlog metrics: failureType={}",
                    failure.getClass().getName());
        }
    }

    private long ageSeconds(Instant createdAt) {
        Instant now = clock.instant();
        if (createdAt.isAfter(now)) {
            return 0L;
        }
        return Math.max(0L, Duration.between(createdAt, now).getSeconds());
    }
}
