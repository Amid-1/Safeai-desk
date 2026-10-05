package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
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
        var retrieval =
                mock(ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService.class);
        var assembler =
                mock(KnowledgeContextAssembler.class);
        var validator =
                mock(KnowledgeCitationValidator.class);
        var contextReceiptService =
                mock(KnowledgeRagContextReceiptService.class);
        var principal =
                mock(SafeAiUserPrincipal.class);

        var service =
                new KnowledgeRagService(
                        retrieval,
                        assembler,
                        validator,
                        contextReceiptService,
                        new KnowledgeRagProperties(
                                8,
                                24_000,
                                6_000
                        )
                );

        UUID user = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID chat = UUID.randomUUID();
        UUID operation = UUID.randomUUID();

        /*
         * Deliberately use the legacy/unreserved request shape.
         *
         * The ChatTurn is governed (modelRouteDecisionId != null), therefore
         * KnowledgeRagService must reject it before retrieval/context assembly
         * because the exact model-route input/output envelope is absent.
         */
        AiChatRequest legacy =
                new AiChatRequest(
                        user,
                        organization,
                        chat,
                        operation,
                        null,
                        null,
                        "Question",
                        List.of()
                );

        ChatProcessingContext governed =
                new ChatProcessingContext(
                        chat,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        operation,
                        UUID.randomUUID(),
                        Instant.now().plusSeconds(60),
                        UUID.randomUUID(),
                        "gpt-4.1",
                        legacy,
                        UUID.randomUUID(),
                        KnowledgeMode.KNOWLEDGE_ONLY,
                        false
                );

        /*
         * A non-null principal is intentional. The current KnowledgeRagService
         * validates the presence of the principal before checking the governed
         * request envelope. No principal identity stubbing is required because
         * the missing-envelope failure occurs before identity comparison.
         */
        assertThatThrownBy(
                () -> service.prepare(
                        governed,
                        principal
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "model-route input/output envelope"
                );

        verifyNoInteractions(
                retrieval,
                assembler,
                validator,
                contextReceiptService
        );
    }
}
