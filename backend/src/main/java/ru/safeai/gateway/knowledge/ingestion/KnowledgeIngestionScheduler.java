package ru.safeai.gateway.knowledge.ingestion;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;

import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        prefix = "safeai.knowledge.ingestion",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class KnowledgeIngestionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    KnowledgeIngestionScheduler.class
            );

    private final KnowledgeIngestionQueueRepository queue;
    private final KnowledgeIngestionMaintenanceService maintenance;
    private final KnowledgeIngestionProcessor processor;
    private final KnowledgeIngestionProperties properties;
    private final Clock clock;
    private final ExecutorService workers;

    public KnowledgeIngestionScheduler(
            KnowledgeIngestionQueueRepository queue,
            KnowledgeIngestionMaintenanceService maintenance,
            KnowledgeIngestionProcessor processor,
            KnowledgeIngestionProperties properties,
            Clock clock
    ) {
        this.queue = queue;
        this.maintenance = maintenance;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
        this.workers = Executors.newFixedThreadPool(
                properties.workerThreads(), runnable -> {
                    Thread thread = new Thread(runnable, "knowledge-ingestion-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Scheduled(
            fixedDelayString =
                    "${safeai.knowledge.ingestion.poll-delay:2s}"
    )
    public void poll() {
        Instant now =
                clock.instant();

        int exhausted =
                maintenance.failExhaustedExpired(
                        now,
                        properties.maxAttempts()
                );

        if (exhausted > 0) {
            log.warn(
                    "Marked {} expired knowledge ingestion jobs as failed",
                    exhausted
            );
        }

        int processed = 0;
        while (processed < properties.batchSize()) {
            // Do not claim work ahead of available execution slots: a
            // queued-but-unstarted claim could expire before its first renewal.
            int slots = Math.min(
                    properties.workerThreads(), properties.batchSize() - processed);
            List<Future<?>> submitted = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                Instant claimedAt = clock.instant();
                KnowledgeIngestionClaim claim = queue.claimNext(
                        claimedAt, claimedAt.plus(properties.processingLease()),
                        properties.maxAttempts()).orElse(null);
                if (claim == null) {
                    break;
                }
                submitted.add(workers.submit(() -> processor.process(claim)));
            }
            if (submitted.isEmpty()) {
                return;
            }
            for (Future<?> task : submitted) {
                try {
                    task.get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Knowledge worker interrupted", error);
                } catch (java.util.concurrent.ExecutionException error) {
                    log.error("Knowledge ingestion worker failed: type={}",
                            error.getCause().getClass().getSimpleName());
                }
            }
            processed += submitted.size();
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
