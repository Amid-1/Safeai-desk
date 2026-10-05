package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteRequestBindingRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelRouteExecutionBindingServiceTest {

    @Test
    void bindRevalidatesSemanticHashAndPersistedBaseBeforeIssuingIdentity() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        ModelRouteExecutionIdentity identity =
                service.bind(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode()
                );

        assertThat(
                identity.decisionId()
        ).isEqualTo(
                scenario.decision().id()
        );

        assertThat(
                identity.chatTurnId()
        ).isEqualTo(
                scenario.turnId()
        );

        assertThat(
                identity.providerOperationId()
        ).isEqualTo(
                scenario.providerOperationId()
        );

        assertThat(
                identity.semanticRequestHash()
        ).isEqualTo(
                scenario.decision()
                        .requestContentHash()
        );

        assertThat(
                identity.retrievalRunId()
        ).isNull();

        assertThat(
                identity.ragContextSha256()
        ).isNull();

        assertThat(
                identity.trustedPreparedRequestSha256()
        ).isNull();

        verify(
                reservedRequests
        ).requireSame(
                scenario.decision().id(),
                scenario.turnId(),
                scenario.providerOperationId(),
                scenario.reserved()
        );

        verify(
                reservedRequests,
                never()
        ).requireKnowledgeReceipt(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void bindKnowledgeRouteCarriesExactDurableRagProvenance() {
        var scenario =
                ModelExecutionBindingTestSupport.knowledgeAssisted();

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        AiChatRequest prepared =
                preparedKnowledgeRequest(
                        scenario.reserved()
                );

        UUID retrievalRunId =
                UUID.randomUUID();

        String ragContextSha256 =
                "c".repeat(
                        64
                );

        String preparedRequestSha256 =
                ModelRouteExecutionIdentity.preparedSha256(
                        prepared
                );

        ModelRouteRequestBindingRepository.KnowledgeRagReceipt receipt =
                new ModelRouteRequestBindingRepository.KnowledgeRagReceipt(
                        retrievalRunId,
                        scenario.turnId(),
                        scenario.decision().organizationId(),
                        scenario.decision().userId(),
                        scenario.knowledgeBaseId(),
                        "embedding-test",
                        ragContextSha256,
                        preparedRequestSha256,
                        ModelTestFixtures.NOW
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        when(
                reservedRequests.requireKnowledgeReceipt(
                        scenario.turnId(),
                        scenario.decision().organizationId(),
                        scenario.decision().userId(),
                        scenario.knowledgeBaseId()
                )
        ).thenReturn(
                receipt
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        ModelRouteExecutionIdentity identity =
                service.bind(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode()
                );

        assertThat(
                identity.knowledgeBaseId()
        ).isEqualTo(
                scenario.knowledgeBaseId()
        );

        assertThat(
                identity.knowledgeMode()
        ).isEqualTo(
                KnowledgeMode.KNOWLEDGE_ASSISTED
        );

        assertThat(
                identity.retrievalRunId()
        ).isEqualTo(
                retrievalRunId
        );

        assertThat(
                identity.ragContextSha256()
        ).isEqualTo(
                ragContextSha256
        );

        assertThat(
                identity.trustedPreparedRequestSha256()
        ).isEqualTo(
                preparedRequestSha256
        );

        verify(
                reservedRequests
        ).requireKnowledgeReceipt(
                scenario.turnId(),
                scenario.decision().organizationId(),
                scenario.decision().userId(),
                scenario.knowledgeBaseId()
        );
    }

    @Test
    void bindRejectsKnowledgeIdentityDriftBeforeBaseSealLookup() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        assertThatThrownBy(
                () -> service.bind(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        UUID.randomUUID(),
                        KnowledgeMode.KNOWLEDGE_ASSISTED
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "semantic route hash"
                );

        verifyNoInteractions(
                reservedRequests
        );
    }

    @Test
    void requireExecutableRejectsTargetOrTurnDriftBeforeRepositoryRead() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteExecutionIdentity identity =
                ModelRouteExecutionIdentity.bind(
                        scenario.decision(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode(),
                        null,
                        null,
                        null
                );

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        assertThatThrownBy(
                () -> service.requireExecutable(
                        identity,
                        UUID.randomUUID(),
                        scenario.decision().id(),
                        scenario.reserved(),
                        scenario.reserved(),
                        identity.provider(),
                        identity.providerModelId()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "Execution target or ChatTurn identity changed"
                );

        verifyNoInteractions(
                repository,
                reservedRequests
        );
    }

    @Test
    void requireExecutableRechecksPersistedDecisionSealSemanticHashAndEnvelope() {
        var scenario =
                ModelExecutionBindingTestSupport.knowledgeAssisted();

        AiChatRequest prepared =
                preparedKnowledgeRequest(
                        scenario.reserved()
                );

        ModelRouteExecutionIdentity identity =
                ModelRouteExecutionIdentity.bind(
                        scenario.decision(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode(),
                        UUID.randomUUID(),
                        "d".repeat(64),
                        ModelRouteExecutionIdentity.preparedSha256(
                                prepared
                        )
                );

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        assertThatCode(
                () -> service.requireExecutable(
                        identity,
                        scenario.turnId(),
                        scenario.decision().id(),
                        scenario.reserved(),
                        prepared,
                        identity.provider(),
                        identity.providerModelId()
                )
        ).doesNotThrowAnyException();

        verify(
                repository
        ).findById(
                scenario.decision().id()
        );

        verify(
                reservedRequests
        ).requireSame(
                scenario.decision().id(),
                scenario.turnId(),
                scenario.providerOperationId(),
                scenario.reserved()
        );

        verify(
                reservedRequests
        ).bindPrepared(
                identity,
                scenario.reserved(),
                prepared
        );
    }

    @Test
    void requireExecutableDelegatesChangedKnowledgePreparedRequestToTrustedReceiptBoundary() {
        var scenario =
                ModelExecutionBindingTestSupport.knowledgeAssisted();

        AiChatRequest trustedPrepared =
                preparedKnowledgeRequest(
                        scenario.reserved()
                );

        ModelRouteExecutionIdentity identity =
                ModelRouteExecutionIdentity.bind(
                        scenario.decision(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode(),
                        UUID.randomUUID(),
                        "e".repeat(64),
                        ModelRouteExecutionIdentity.preparedSha256(
                                trustedPrepared
                        )
                );

        AiChatRequest changedPrepared =
                scenario.reserved()
                        .withInstructions(
                                appendTrustedContext(
                                        scenario.reserved().systemInstructions(),
                                        "different system context"
                                ),
                                appendTrustedContext(
                                        scenario.reserved().developerInstructions(),
                                        "different developer context"
                                )
                        );

        assertThat(
                ModelRouteExecutionIdentity.preparedSha256(
                        changedPrepared
                )
        )
                .as(
                        "Test precondition: changed prepared request must differ "
                                + "from the trusted Knowledge receipt hash"
                )
                .isNotEqualTo(
                        identity.trustedPreparedRequestSha256()
                );

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        /*
         * ModelRouteExecutionBindingService owns persisted decision/base/envelope
         * validation. Exact V64 Knowledge receipt validation and durable prepared
         * binding are owned by ModelRouteReservedRequestService.bindPrepared(...).
         *
         * A Mockito mock does not execute that collaborator's validation logic,
         * so this boundary test must verify exact delegation rather than expect
         * the mocked collaborator to throw by itself.
         */
        assertThatCode(
                () -> service.requireExecutable(
                        identity,
                        scenario.turnId(),
                        scenario.decision().id(),
                        scenario.reserved(),
                        changedPrepared,
                        identity.provider(),
                        identity.providerModelId()
                )
        ).doesNotThrowAnyException();

        verify(
                reservedRequests
        ).bindPrepared(
                identity,
                scenario.reserved(),
                changedPrepared
        );
    }

    @Test
    void requireExecutablePropagatesTrustedReceiptRejectionFromBindingBoundary() {
        var scenario =
                ModelExecutionBindingTestSupport.knowledgeAssisted();

        AiChatRequest trustedPrepared =
                preparedKnowledgeRequest(
                        scenario.reserved()
                );

        ModelRouteExecutionIdentity identity =
                ModelRouteExecutionIdentity.bind(
                        scenario.decision(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.providerOperationId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode(),
                        UUID.randomUUID(),
                        "f".repeat(64),
                        ModelRouteExecutionIdentity.preparedSha256(
                                trustedPrepared
                        )
                );

        AiChatRequest changedPrepared =
                scenario.reserved()
                        .withInstructions(
                                appendTrustedContext(
                                        scenario.reserved().systemInstructions(),
                                        "different system context"
                                ),
                                appendTrustedContext(
                                        scenario.reserved().developerInstructions(),
                                        "different developer context"
                                )
                        );

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        doThrow(
                new IllegalStateException(
                        "Prepared request differs from trusted Knowledge RAG receipt"
                )
        ).when(
                reservedRequests
        ).bindPrepared(
                identity,
                scenario.reserved(),
                changedPrepared
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        assertThatThrownBy(
                () -> service.requireExecutable(
                        identity,
                        scenario.turnId(),
                        scenario.decision().id(),
                        scenario.reserved(),
                        changedPrepared,
                        identity.provider(),
                        identity.providerModelId()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "trusted Knowledge RAG receipt"
                );

        verify(
                reservedRequests
        ).bindPrepared(
                identity,
                scenario.reserved(),
                changedPrepared
        );
    }

    @Test
    void requiresSinglePhysicalAttemptUsesPersistedDecisionThenRepositoryPolicySnapshot() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteDecisionRepository repository =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteReservedRequestService reservedRequests =
                mock(
                        ModelRouteReservedRequestService.class
                );

        when(
                repository.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        when(
                repository.requiresSinglePhysicalAttempt(
                        scenario.decision().id()
                )
        ).thenReturn(
                true
        );

        ModelRouteExecutionBindingService service =
                new ModelRouteExecutionBindingService(
                        repository,
                        scenario.normalizer(),
                        reservedRequests
                );

        assertThat(
                service.requiresSinglePhysicalAttempt(
                        scenario.decision().id()
                )
        ).isTrue();

        verify(
                repository
        ).requiresSinglePhysicalAttempt(
                scenario.decision().id()
        );

        verify(
                reservedRequests,
                never()
        ).requireSame(
                any(),
                any(),
                any(),
                any()
        );
    }

    private static AiChatRequest preparedKnowledgeRequest(
            AiChatRequest reserved
    ) {
        return reserved.withInstructions(
                appendTrustedContext(
                        reserved.systemInstructions(),
                        "trusted system RAG context"
                ),
                appendTrustedContext(
                        reserved.developerInstructions(),
                        "trusted developer RAG context"
                )
        );
    }

    private static String appendTrustedContext(
            String base,
            String context
    ) {
        if (base == null || base.isBlank()) {
            return context;
        }
        return base + "\n\n" + context;
    }
}
