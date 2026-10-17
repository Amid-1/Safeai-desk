package ru.safeai.gateway.knowledge.storage.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Conservative gauge: failed refreshes do not silently turn incidents into zero. */
@Component
public final class KnowledgeStorageReviewMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(
            KnowledgeStorageReviewMetrics.class);
    private final KnowledgeStorageUploadJournal journal;
    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong(-1L);

    public KnowledgeStorageReviewMetrics(
            KnowledgeStorageUploadJournal journal, MeterRegistry registry) {
        this.journal = Objects.requireNonNull(journal);
        this.registry = Objects.requireNonNull(registry);
        Gauge.builder("safeai.knowledge.storage.needs.review.rows", pending,
                        AtomicLong::get)
                .description("Unresolved S3 intents requiring human review; -1 = unknown")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${safeai.knowledge.storage-review.metrics-delay:60s}")
    public void refresh() {
        try {
            pending.set(journal.countNeedsReview());
        } catch (RuntimeException exception) {
            pending.set(-1L);
            registry.counter("safeai.knowledge.storage.review.metrics.failures").increment();
            LOG.warn("Knowledge S3 review metrics unavailable: type={}",
                    exception.getClass().getSimpleName());
        }
    }
}
