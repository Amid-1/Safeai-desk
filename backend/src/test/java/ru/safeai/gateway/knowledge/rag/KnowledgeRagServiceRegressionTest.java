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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.CHAT_ID;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.KB_ID;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.NOW;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.OP_ID;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.RUN_ID;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.TURN_ID;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.hit;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.preparation;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.request;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.response;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.retrieval;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class KnowledgeRagServiceRegressionTest {

    @Mock
    KnowledgeRetrievalService retrieval;

    @Mock
    KnowledgeContextAssembler assembler;

    @Mock
    KnowledgeCitationValidator citationValidator;

    @Mock
    KnowledgeRagContextReceiptService contextReceiptService;

    @Mock
    SafeAiUserPrincipal principal;

    @Test
    void generalModeNeverCallsRetrievalOrMaterializesKnowledgeContext() {
        var processingContext =
                context(
                        KnowledgeMode.GENERAL
                );

        var expectedRequest =
                processingContext.aiRequest();

        var service =
                service();

        var prepared =
                service.prepare(
                        processingContext,
                        principal
                );

        assertThat(prepared.mode())
                .isEqualTo(
                        KnowledgeMode.GENERAL
                );
        assertThat(prepared.aiRequest())
                .isEqualTo(
                        expectedRequest
                );
        assertThat(prepared.sources())
                .isEmpty();

        verifyNoInteractions(
                retrieval,
                assembler,
                contextReceiptService
        );
    }

    @Test
    void knowledgeOnlyPropagatesChatTurnScopeIntoRetrievalAssemblerAndDurableReceipt() {
        var processingContext =
                context(
                        KnowledgeMode.KNOWLEDGE_ONLY
                );

        var baseRequest =
                processingContext.aiRequest();

        /*
         * Current KnowledgeRagService validates the authenticated principal
         * against the immutable base request before retrieval.
         */
        when(principal.getOrganizationId())
                .thenReturn(
                        baseRequest.organizationId()
                );
        when(principal.getId())
                .thenReturn(
                        baseRequest.userId()
                );

        var execution =
                retrieval(
                        hit(
                                0,
                                "Evidence"
                        )
                );

        var preparedRequest =
                baseRequest.withInstructions(
                        baseRequest.systemInstructions(),
                        "Knowledge context"
                );

        var assembled =
                new KnowledgeContextAssembler.AssembledContext(
                        "e".repeat(64),
                        java.util.List.of(
                                new KnowledgeContextSource(
                                        "C1",
                                        hit(
                                                0,
                                                "Evidence"
                                        )
                                )
                        ),
                        preparedRequest
                );

        when(
                retrieval.retrieveForChat(
                        eq(KB_ID),
                        eq(TURN_ID),
                        eq("Question"),
                        eq(8),
                        same(principal)
                )
        ).thenReturn(
                execution
        );

        when(
                assembler.assemble(
                        eq(KnowledgeMode.KNOWLEDGE_ONLY),
                        eq(execution),
                        same(baseRequest)
                )
        ).thenReturn(
                assembled
        );

        var prepared =
                service().prepare(
                        processingContext,
                        principal
                );

        assertThat(prepared.knowledgeBaseId())
                .isEqualTo(
                        KB_ID
                );
        assertThat(prepared.retrievalRunId())
                .isEqualTo(
                        RUN_ID
                );
        assertThat(prepared.contextSha256())
                .isEqualTo(
                        "e".repeat(64)
                );
        assertThat(prepared.aiRequest())
                .isSameAs(
                        assembled.request()
                );

        verify(retrieval).retrieveForChat(
                KB_ID,
                TURN_ID,
                "Question",
                8,
                principal
        );

        verify(assembler).assemble(
                KnowledgeMode.KNOWLEDGE_ONLY,
                execution,
                baseRequest
        );

        /*
         * V64 provenance contract: a successful Knowledge preparation must
         * persist the exact retrieval/context/prepared-request receipt before
         * control returns to the physical execution boundary.
         */
        verify(contextReceiptService).record(
                execution.retrievalRunId(),
                TURN_ID,
                baseRequest.organizationId(),
                baseRequest.userId(),
                KB_ID,
                execution.embeddingModel(),
                "e".repeat(64),
                assembled.request()
        );
    }

    @Test
    void completionDelegatesToCitationValidationWithoutRewritingItsEvidence() {
        var prepared =
                preparation(
                        KnowledgeMode.KNOWLEDGE_ONLY,
                        1
                );
        var providerResponse =
                response(
                        "Claim [C1]"
                );

        var expected =
                new KnowledgeCitationValidator()
                        .validate(
                                prepared,
                                providerResponse
                        );

        when(
                citationValidator.validate(
                        prepared,
                        providerResponse
                )
        ).thenReturn(
                expected
        );

        assertThat(
                service().complete(
                        prepared,
                        providerResponse
                )
        ).isSameAs(
                expected
        );

        verifyNoInteractions(
                retrieval,
                assembler,
                contextReceiptService
        );
    }

    private KnowledgeRagService service() {
        return new KnowledgeRagService(
                retrieval,
                assembler,
                citationValidator,
                contextReceiptService,
                new KnowledgeRagProperties(
                        8,
                        1_000,
                        300
                )
        );
    }

    private ChatProcessingContext context(
            KnowledgeMode mode
    ) {
        return new ChatProcessingContext(
                CHAT_ID,
                TURN_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OP_ID,
                UUID.randomUUID(),
                NOW.plusSeconds(60),
                UUID.randomUUID(),
                "model",
                request(
                        4_096L,
                        100
                ),
                mode.usesKnowledge()
                        ? KB_ID
                        : null,
                mode,
                false
        );
    }
}
