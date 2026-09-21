package ru.safeai.gateway.knowledge.testsupport;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;
import ru.safeai.gateway.knowledge.rag.KnowledgeContextSource;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.knowledge.rag.RagPreparation;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KnowledgeRegressionFixtures {
    public static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    public static final UUID ORG_ID = UUID.fromString("10000000-0000-4000-8000-000000000011");
    public static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000012");
    public static final UUID CHAT_ID = UUID.fromString("10000000-0000-4000-8000-000000000013");
    public static final UUID TURN_ID = UUID.fromString("10000000-0000-4000-8000-000000000014");
    public static final UUID OP_ID = UUID.fromString("10000000-0000-4000-8000-000000000015");
    public static final UUID KB_ID = UUID.fromString("10000000-0000-4000-8000-000000000016");
    public static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000017");

    private KnowledgeRegressionFixtures() { }

    public static AiChatRequest request(Long reservedInput, Integer maxOutput) {
        return new AiChatRequest(USER_ID, ORG_ID, CHAT_ID, OP_ID,
                "Application system", "Application developer", "Question",
                List.of(), reservedInput, maxOutput);
    }

    public static AiChatResponse response(String content) {
        return AiChatResponse.fromProvider(content, "model", "model", "message-id", "request-id",
                AiResponseStatus.COMPLETED, "completed", 7, 3, PricingResult.unpriced(NOW));
    }

    public static KnowledgeRetrievalHit hit(int ordinal, String text) {
        return new KnowledgeRetrievalHit(
                UUID.nameUUIDFromBytes(("chunk-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("doc-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("version-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Policy.pdf", 1, ordinal, text, 3, 3, "Rules", .02, ordinal + 1,
                ordinal + 1, .7f, .8f, "a".repeat(64));
    }

    public static KnowledgeRetrievalExecution retrieval(KnowledgeRetrievalHit... hits) {
        return new KnowledgeRetrievalExecution(RUN_ID, KB_ID, TURN_ID, "b".repeat(64),
                "hashing-v1", NOW, List.of(hits));
    }

    public static RagPreparation preparation(KnowledgeMode mode, int sourceCount) {
        var sources = java.util.stream.IntStream.range(0, sourceCount)
                .mapToObj(index -> new KnowledgeContextSource("C" + (index + 1),
                        hit(index, "Approved policy content " + index)))
                .toList();
        return new RagPreparation(mode, KB_ID, RUN_ID, "hashing-v1",
                "c".repeat(64), sources, request(null, null));
    }
}
