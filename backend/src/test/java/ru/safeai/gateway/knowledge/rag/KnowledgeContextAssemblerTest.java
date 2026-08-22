package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.knowledge.config.KnowledgeRagProperties;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeContextAssemblerTest {

    @Test
    void buildsBoundedUntrustedContextWithStableLabels() {
        KnowledgeContextAssembler assembler = new KnowledgeContextAssembler(
                new KnowledgeRagProperties(8, 1_000, 300)
        );
        KnowledgeRetrievalExecution retrieval = new KnowledgeRetrievalExecution(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChatTestFixtures.TURN_ID,
                "a".repeat(64),
                "embedding-v1",
                Instant.now(),
                List.of(hit("first", 0), hit("second", 1))
        );

        var assembled = assembler.assemble(
                KnowledgeMode.KNOWLEDGE_ONLY,
                retrieval,
                request()
        );

        assertThat(assembled.sources()).hasSizeBetween(1, 2);
        assertThat(assembled.sources().getFirst().label()).isEqualTo("C1");
        assertThat(assembled.request().developerInstructions())
                .contains("KNOWLEDGE-ONLY MODE", "[C1]", "untrusted data");
        assertThat(assembled.contextSha256()).matches("[0-9a-f]{64}");
        assertThat(assembled.request().developerInstructions().length())
                .isLessThan(3_000);
    }

    private static KnowledgeRetrievalHit hit(String name, int ordinal) {
        return new KnowledgeRetrievalHit(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                name, 1, ordinal, "content ".repeat(80),
                1, 1, null, 0.02, 1, 1, 0.5f, 0.8f,
                "b".repeat(64)
        );
    }

    private static AiChatRequest request() {
        return new AiChatRequest(
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                null,
                null,
                "Question",
                List.of()
        );
    }
}
