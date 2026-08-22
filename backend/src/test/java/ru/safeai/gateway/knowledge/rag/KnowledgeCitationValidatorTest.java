package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCitationValidatorTest {

    private final KnowledgeCitationValidator validator =
            new KnowledgeCitationValidator();

    @Test
    void acceptsOnlyCitationsPresentInPreparedContext() {
        RagPreparation preparation = preparation(KnowledgeMode.KNOWLEDGE_ONLY);
        var response = response("Факт подтверждён [C1].");

        RagCompletion completion = validator.validate(preparation, response);

        assertThat(completion.response().content())
                .isEqualTo("Факт подтверждён [C1].");
        assertThat(completion.citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.label()).isEqualTo("C1");
                    assertThat(citation.chunkId())
                            .isEqualTo(preparation.sources().getFirst().hit().chunkId());
                });
        assertThat(completion.citationsValid()).isTrue();
        assertThat(completion.evidenceSufficient()).isTrue();
    }

    @Test
    void knowledgeOnlyFailsClosedForInventedCitation() {
        RagCompletion completion = validator.validate(
                preparation(KnowledgeMode.KNOWLEDGE_ONLY),
                response("Неподтверждённый факт [C99].")
        );

        assertThat(completion.response().content())
                .isEqualTo(KnowledgeCitationValidator.ABSTENTION);
        assertThat(completion.citations()).isEmpty();
        assertThat(completion.citationsValid()).isFalse();
        assertThat(completion.evidenceSufficient()).isFalse();
    }

    @Test
    void assistedModeRemovesInventedMarkerWithoutClaimingEvidence() {
        RagCompletion completion = validator.validate(
                preparation(KnowledgeMode.KNOWLEDGE_ASSISTED),
                response("Общее рассуждение [C42].")
        );

        assertThat(completion.response().content())
                .isEqualTo("Общее рассуждение .");
        assertThat(completion.citationsValid()).isFalse();
        assertThat(completion.evidenceSufficient()).isFalse();
    }

    private RagPreparation preparation(KnowledgeMode mode) {
        KnowledgeRetrievalHit hit = new KnowledgeRetrievalHit(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Policy.pdf",
                3,
                7,
                "Only approved models may be used.",
                2,
                2,
                "Model policy",
                0.02,
                1,
                1,
                0.8f,
                0.9f,
                "a".repeat(64)
        );
        return new RagPreparation(
                mode,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "embedding-v1",
                "b".repeat(64),
                List.of(new KnowledgeContextSource("C1", hit)),
                request()
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

    private static ru.safeai.gateway.ai.dto.AiChatResponse response(
            String content
    ) {
        var original = ChatTestFixtures.freeResponse();
        return new ru.safeai.gateway.ai.dto.AiChatResponse(
                content,
                original.requestedModel(),
                original.model(),
                original.providerMessageId(),
                original.providerRequestId(),
                original.responseStatus(),
                original.finishReason(),
                original.inputTokens(),
                original.outputTokens(),
                original.usageStatus(),
                original.costUsd(),
                original.pricingStatus(),
                original.currency(),
                original.priceVersion(),
                original.pricingCalculatedAt()
        );
    }
}
