package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.provider.ProviderDataEvidence;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeProviderDataGuard;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingDataAssuranceTest {
    @Test
    void missingExpiredMismatchedOrPartialEvidenceNeverDispatches() {
        var p = new KnowledgeEmbeddingProperties("openai", null, "test-key",
                "text-embedding-3-small", 384, 16, 20000,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        var now = EmbeddingAssuranceTestSupport.NOW;
        var clock = EmbeddingAssuranceTestSupport.CLOCK;
        var binding = KnowledgeProviderDataGuard.embeddingBinding(p);
        var cases = Arrays.asList(null,
                new ProviderDataEvidence("DOC-TEST", now.minusSeconds(2), now, binding, true, true),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), "0".repeat(64), true, true),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), binding, false, true),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), binding, true, false));
        for (var evidence : cases) {
            var builder = RestClient.builder().baseUrl(p.baseUrl());
            var server = MockRestServiceServer.bindTo(builder).build();
            var provider = new OpenAiKnowledgeEmbeddingProvider(p, builder.build(), evidence, clock);
            assertThatThrownBy(() -> provider.embed("confidential"))
                    .isInstanceOf(KnowledgeEmbeddingException.class)
                    .extracting("code").isEqualTo("EMBEDDING_DATA_ASSURANCE_DENIED");
            server.verify();
        }
    }
}
