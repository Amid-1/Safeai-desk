package ru.safeai.gateway.knowledge.embedding;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

@Component
@Profile({"prod", "production"})
public class KnowledgeEmbeddingProductionInvariantVerifier {

    private final KnowledgeEmbeddingProperties properties;

    public KnowledgeEmbeddingProductionInvariantVerifier(
            KnowledgeEmbeddingProperties properties
    ) {
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        if (!"openai".equals(
                properties.provider()
        )) {
            throw new IllegalStateException(
                    "Production Knowledge embedding provider должен быть "
                            + "production-grade provider. "
                            + "hashing разрешён только для local/test/demo."
            );
        }
    }
}
