package ru.safeai.gateway.knowledge.embedding;

import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.provider.ProviderDataEvidence;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeProviderDataGuard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

final class EmbeddingAssuranceTestSupport {
    static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static OpenAiKnowledgeEmbeddingProvider provider(KnowledgeEmbeddingProperties properties,
                                                     RestClient client) {
        return new OpenAiKnowledgeEmbeddingProvider(properties, client,
                new ProviderDataEvidence("DOC-TEST_ONLY", NOW.minusSeconds(1), NOW.plusSeconds(3600),
                        KnowledgeProviderDataGuard.embeddingBinding(properties), true, true), CLOCK);
    }
}
