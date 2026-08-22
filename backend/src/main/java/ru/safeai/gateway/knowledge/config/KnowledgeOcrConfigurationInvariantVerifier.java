package ru.safeai.gateway.knowledge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class KnowledgeOcrConfigurationInvariantVerifier {

    private final KnowledgeOcrProperties ocr;
    private final KnowledgeIngestionProperties ingestion;

    public KnowledgeOcrConfigurationInvariantVerifier(
            KnowledgeOcrProperties ocr,
            KnowledgeIngestionProperties ingestion
    ) {
        this.ocr = ocr;
        this.ingestion = ingestion;
    }

    @PostConstruct
    void verify() {
        if (!"http".equals(
                ocr.provider()
        )) {
            return;
        }

        Duration providerBudget =
                ocr.connectTimeout()
                        .plus(
                                ocr.readTimeout()
                        );

        if (providerBudget.compareTo(
                ingestion.extractionTimeout()
        ) > 0) {
            throw new IllegalStateException(
                    "safeai.knowledge.ocr connect-timeout + read-timeout "
                            + "не должны превышать "
                            + "safeai.knowledge.ingestion.extraction-timeout"
            );
        }
    }
}
