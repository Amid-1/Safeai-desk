package ru.safeai.gateway.knowledge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.config.ProviderSecretFile;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;

import java.nio.file.Path;

/** Keeps the AI module independent of Knowledge while sharing the file contract. */
@Component
@Conditional(ProductionEnvironmentCondition.class)
@Lazy(false)
final class KnowledgeProviderSecretConfigurationValidator {
    private final KnowledgeEmbeddingProperties embedding;
    private final KnowledgeOcrProperties ocr;

    KnowledgeProviderSecretConfigurationValidator(KnowledgeEmbeddingProperties embedding,
                                                  KnowledgeOcrProperties ocr) {
        this.embedding = embedding;
        this.ocr = ocr;
    }

    @PostConstruct
    void validate() {
        if ("openai".equals(embedding.provider())) {
            ProviderSecretFile.requireMatches(Path.of("/run/secrets/OPENAI_EMBEDDING_API_KEY"), embedding.apiKey());
        }
        if ("http".equals(ocr.provider())) {
            ProviderSecretFile.requireMatches(Path.of("/run/secrets/KNOWLEDGE_OCR_API_KEY"), ocr.apiKey());
        }
    }
}
