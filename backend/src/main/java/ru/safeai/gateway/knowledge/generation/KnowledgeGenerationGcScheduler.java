package ru.safeai.gateway.knowledge.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.generation.gc.enabled",
        havingValue = "true"
)
public class KnowledgeGenerationGcScheduler {

    private final KnowledgeGenerationGc gc;
    private final Duration retention;
    private final int keepLatestReindex;
    private final int keepLatestSupersededVersions;
    private final int batchSize;

    public KnowledgeGenerationGcScheduler(
            KnowledgeGenerationGc gc,
            @Value("${safeai.knowledge.generation.gc.retention:P30D}")
            Duration retention,
            @Value("${safeai.knowledge.generation.gc.keep-latest-reindex:${safeai.knowledge.generation.gc.keep-latest:2}}")
            int keepLatestReindex,
            @Value("${safeai.knowledge.generation.gc.keep-latest-superseded-versions:0}")
            int keepLatestSupersededVersions,
            @Value("${safeai.knowledge.generation.gc.batch-size:20}")
            int batchSize
    ) {
        KnowledgeGenerationGc.validate(
                retention,
                keepLatestReindex,
                keepLatestSupersededVersions,
                batchSize
        );
        this.gc = gc;
        this.retention = retention;
        this.keepLatestReindex = keepLatestReindex;
        this.keepLatestSupersededVersions = keepLatestSupersededVersions;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${safeai.knowledge.generation.gc.interval:PT1H}")
    public void collect() {
        gc.collect(
                retention,
                keepLatestReindex,
                keepLatestSupersededVersions,
                batchSize
        );
    }
}
