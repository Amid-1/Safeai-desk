package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.knowledge.config.KnowledgeRagProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeRagGovernedEnvelopeTest {

    @Test
    void sealedGovernedKnowledgeTurnCannotEnterRetrievalWithoutItsReservation() {
        var retrieval = mock(ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService.class);
        var assembler = mock(KnowledgeContextAssembler.class);
        var validator = mock(KnowledgeCitationValidator.class);
        var service = new KnowledgeRagService(retrieval, assembler, validator,
                new KnowledgeRagProperties(8, 24000, 6000));
        UUID user = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID chat = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        AiChatRequest legacy = new AiChatRequest(
                user, organization, chat, operation, null, null, "Question", List.of());
        ChatProcessingContext governed = new ChatProcessingContext(
                chat, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), operation,
                UUID.randomUUID(), Instant.now().plusSeconds(60), UUID.randomUUID(), "gpt-4.1",
                legacy, UUID.randomUUID(), KnowledgeMode.KNOWLEDGE_ONLY, false);
        assertThatThrownBy(() -> service.prepare(governed, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-route input/output envelope");
        verifyNoInteractions(retrieval, assembler, validator);
    }
}
