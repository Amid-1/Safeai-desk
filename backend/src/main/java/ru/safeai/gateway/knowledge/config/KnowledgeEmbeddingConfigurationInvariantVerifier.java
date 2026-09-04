package ru.safeai.gateway.knowledge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Component
public class KnowledgeEmbeddingConfigurationInvariantVerifier {

    private static final Duration SAFETY_MARGIN =
            Duration.ofSeconds(5);

    private final KnowledgeEmbeddingProperties embedding;
    private final KnowledgeIngestionProperties ingestion;

    public KnowledgeEmbeddingConfigurationInvariantVerifier(
            KnowledgeEmbeddingProperties embedding,
            KnowledgeIngestionProperties ingestion
    ) {
        this.embedding = Objects.requireNonNull(
                embedding,
                "embedding не должен быть null"
        );
        this.ingestion = Objects.requireNonNull(
                ingestion,
                "ingestion не должен быть null"
        );
    }

    @PostConstruct
    void verify() {
        if (!"openai".equals(
                embedding.provider()
        )) {
            return;
        }

        Duration providerBudget =
                embedding.connectTimeout()
                        .plus(
                                embedding.readTimeout()
                        )
                        .plus(
                                SAFETY_MARGIN
                        );

        if (providerBudget.compareTo(
                ingestion.processingLease()
        ) >= 0) {
            throw new IllegalStateException(
                    "safeai.knowledge.embedding connect-timeout + read-timeout "
                            + "+ 5s safety margin должны быть меньше "
                            + "safeai.knowledge.ingestion.processing-lease"
            );
        }
    }
}
