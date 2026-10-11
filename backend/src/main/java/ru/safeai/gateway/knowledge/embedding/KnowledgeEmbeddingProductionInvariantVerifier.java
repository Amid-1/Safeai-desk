
package ru.safeai.gateway.knowledge.embedding;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

@Component
@Conditional(ProductionEnvironmentCondition.class)
public class KnowledgeEmbeddingProductionInvariantVerifier {

    private final KnowledgeEmbeddingProperties properties;

    public KnowledgeEmbeddingProductionInvariantVerifier(
            KnowledgeEmbeddingProperties properties
    ) {
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        if (!"openai".equals(properties.provider())) {
            throw new IllegalStateException(
                    "Production Knowledge embedding provider должен быть "
                            + "production-grade provider. "
                            + "hashing разрешен только для local/test/demo."
            );
        }
    }
}
