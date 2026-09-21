package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.config.KnowledgeRagProperties;
import ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class KnowledgeRagServiceRegressionTest {
    @Mock KnowledgeRetrievalService retrieval;
    @Mock KnowledgeContextAssembler assembler;
    @Mock KnowledgeCitationValidator citationValidator;
    @Mock SafeAiUserPrincipal principal;

    @Test
    void generalModeNeverCallsRetrievalOrMaterializesKnowledgeContext() {
        var request = request(4096L, 100);
        var service = service();
        var prepared = service.prepare(context(KnowledgeMode.GENERAL), principal);
        assertThat(prepared.mode()).isEqualTo(KnowledgeMode.GENERAL);
        assertThat(prepared.aiRequest()).isEqualTo(request);
        assertThat(prepared.sources()).isEmpty();
        verifyNoInteractions(retrieval, assembler);
    }

    @Test
    void knowledgeOnlyPropagatesChatTurnScopeIntoRetrievalAndAssembler() {
        var execution = retrieval(hit(0, "Evidence"));
        var assembled = new KnowledgeContextAssembler.AssembledContext("e".repeat(64),
                java.util.List.of(new KnowledgeContextSource("C1", hit(0, "Evidence"))), request(4096L, 100));
        when(retrieval.retrieveForChat(eq(KB_ID), eq(TURN_ID), eq("Question"), eq(8), same(principal)))
                .thenReturn(execution);
        when(assembler.assemble(eq(KnowledgeMode.KNOWLEDGE_ONLY), eq(execution), any()))
                .thenReturn(assembled);
        var prepared = service().prepare(context(KnowledgeMode.KNOWLEDGE_ONLY), principal);
        assertThat(prepared.knowledgeBaseId()).isEqualTo(KB_ID);
        assertThat(prepared.retrievalRunId()).isEqualTo(RUN_ID);
        assertThat(prepared.contextSha256()).isEqualTo("e".repeat(64));
        assertThat(prepared.aiRequest()).isSameAs(assembled.request());
        verify(retrieval).retrieveForChat(KB_ID, TURN_ID, "Question", 8, principal);
    }

    @Test
    void completionDelegatesToCitationValidationWithoutRewritingItsEvidence() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        var providerResponse = response("Claim [C1]");
        var expected = new KnowledgeCitationValidator().validate(prepared, providerResponse);
        when(citationValidator.validate(prepared, providerResponse)).thenReturn(expected);
        assertThat(service().complete(prepared, providerResponse)).isSameAs(expected);
        verifyNoInteractions(retrieval, assembler);
    }

    private KnowledgeRagService service() {
        return new KnowledgeRagService(retrieval, assembler, citationValidator,
                new KnowledgeRagProperties(8, 1_000, 300));
    }

    private ChatProcessingContext context(KnowledgeMode mode) {
        return new ChatProcessingContext(CHAT_ID, TURN_ID, UUID.randomUUID(), UUID.randomUUID(), OP_ID,
                UUID.randomUUID(), NOW.plusSeconds(60), UUID.randomUUID(), "model",
                request(4096L, 100), mode.usesKnowledge() ? KB_ID : null, mode, false);
    }
}
