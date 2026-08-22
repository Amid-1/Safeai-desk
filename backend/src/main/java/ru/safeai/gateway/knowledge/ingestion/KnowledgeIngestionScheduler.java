package ru.safeai.gateway.knowledge.ingestion;

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

    private static final Logger log = LoggerFactory.getLogger(
            KnowledgeIngestionScheduler.class
    );

    private final KnowledgeIngestionQueueRepository queue;
    private final KnowledgeIngestionProcessor processor;
    private final KnowledgeIngestionProperties properties;
    private final Clock clock;

    public KnowledgeIngestionScheduler(
            KnowledgeIngestionQueueRepository queue,
            KnowledgeIngestionProcessor processor,
            KnowledgeIngestionProperties properties,
            Clock clock
    ) {
        this.queue = queue;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${safeai.knowledge.ingestion.poll-delay:2s}"
    )
    public void poll() {
        Instant now = clock.instant();
        int exhausted = queue.failExhaustedExpired(
                now,
                properties.maxAttempts()
        );
        if (exhausted > 0) {
            log.warn(
                    "Marked {} expired knowledge ingestion jobs as failed",
                    exhausted
            );
        }

        for (int index = 0; index < properties.batchSize(); index++) {
            Instant claimedAt = clock.instant();
            KnowledgeIngestionClaim claim = queue.claimNext(
                    claimedAt,
                    claimedAt.plus(properties.processingLease()),
                    properties.maxAttempts()
            ).orElse(null);
            if (claim == null) {
                return;
            }
            processor.process(claim);
        }
    }
}
