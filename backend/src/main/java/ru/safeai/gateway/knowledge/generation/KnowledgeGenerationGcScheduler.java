package ru.safeai.gateway.knowledge.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@ConditionalOnProperty(name="safeai.knowledge.generation.gc.enabled", havingValue="true")
public class KnowledgeGenerationGcScheduler {
    private final KnowledgeGenerationGc gc;
    private final Duration retention;
    private final int keepLatest;
    private final int batchSize;

    public KnowledgeGenerationGcScheduler(KnowledgeGenerationGc gc,
            @Value("${safeai.knowledge.generation.gc.retention:P30D}") Duration retention,
            @Value("${safeai.knowledge.generation.gc.keep-latest:2}") int keepLatest,
            @Value("${safeai.knowledge.generation.gc.batch-size:20}") int batchSize) {
        KnowledgeGenerationGc.validate(retention,keepLatest,batchSize);
        this.gc=gc; this.retention=retention; this.keepLatest=keepLatest; this.batchSize=batchSize;
    }

    @Scheduled(fixedDelayString="${safeai.knowledge.generation.gc.interval:PT1H}")
    public void collect() { gc.collect(retention,keepLatest,batchSize); }
}
