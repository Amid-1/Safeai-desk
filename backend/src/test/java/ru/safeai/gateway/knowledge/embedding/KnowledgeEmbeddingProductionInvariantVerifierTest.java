package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeEmbeddingProductionInvariantVerifierTest {
    @Test
    void localHashingCannotStartUnderProductionVerifier() {
        var properties = new KnowledgeEmbeddingProperties("hashing", null, null, null,
                384, null, null, null, null);
        assertThatThrownBy(new KnowledgeEmbeddingProductionInvariantVerifier(properties)::verify)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hashing");
    }

    @Test
    void configuredProductionGradeProviderPasses() {
        var properties = new KnowledgeEmbeddingProperties("openai", null, "test-key", null,
                384, 2, 1_000, null, null);
        assertThatCode(new KnowledgeEmbeddingProductionInvariantVerifier(properties)::verify)
                .doesNotThrowAnyException();
    }
}
