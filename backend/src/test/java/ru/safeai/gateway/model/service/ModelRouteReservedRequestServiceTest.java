package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteRequestBindingRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelRouteReservedRequestServiceTest {

    @Test
    void sealPersistsTheExactPreRagBaseHashForMatchingV3Evidence() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteDecisionRepository decisions =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteRequestBindingRepository bindings =
                mock(
                        ModelRouteRequestBindingRepository.class
                );

        String expectedBaseSha256 =
                ModelRouteExecutionIdentity.baseSha256(
                        scenario.reserved()
                );

        var expectedCreatedAt =
                DatabaseTimestampNormalizer.normalize(
                        ModelTestFixtures.NOW
                );

        when(
                decisions.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        when(
                bindings.insertReserved(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId(),
                        expectedBaseSha256,
                        expectedCreatedAt
                )
        ).thenReturn(
                1
        );

        ModelRouteReservedRequestService service =
                new ModelRouteReservedRequestService(
                        decisions,
                        bindings,
                        scenario.normalizer(),
                        ModelTestFixtures.CLOCK
                );

        assertThatCode(
                () -> service.seal(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode()
                )
        ).doesNotThrowAnyException();

        verify(
                bindings
        ).insertReserved(
                scenario.decision().id(),
                scenario.turnId(),
                scenario.providerOperationId(),
                expectedBaseSha256,
                expectedCreatedAt
        );
    }

    @Test
    void sealRejectsSemanticModeDriftBeforeAnyBindingWrite() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteDecisionRepository decisions =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteRequestBindingRepository bindings =
                mock(
                        ModelRouteRequestBindingRepository.class
                );

        when(
                decisions.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        ModelRouteReservedRequestService service =
                new ModelRouteReservedRequestService(
                        decisions,
                        bindings,
                        scenario.normalizer(),
                        ModelTestFixtures.CLOCK
                );

        assertThatThrownBy(
                () -> service.seal(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.reserved(),
                        UUID.randomUUID(),
                        ru.safeai.gateway.knowledge.rag.KnowledgeMode.KNOWLEDGE_ASSISTED
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "different from routed evidence"
                );

        verifyNoInteractions(
                bindings
        );
    }

    @Test
    void sealRejectsUnexpectedRepositoryWriteCount() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteDecisionRepository decisions =
                mock(
                        ModelRouteDecisionRepository.class
                );

        ModelRouteRequestBindingRepository bindings =
                mock(
                        ModelRouteRequestBindingRepository.class
                );

        String expectedBaseSha256 =
                ModelRouteExecutionIdentity.baseSha256(
                        scenario.reserved()
                );

        var expectedCreatedAt =
                DatabaseTimestampNormalizer.normalize(
                        ModelTestFixtures.NOW
                );

        when(
                decisions.findById(
                        scenario.decision().id()
                )
        ).thenReturn(
                Optional.of(
                        scenario.decision()
                )
        );

        when(
                bindings.insertReserved(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId(),
                        expectedBaseSha256,
                        expectedCreatedAt
                )
        ).thenReturn(
                0
        );

        ModelRouteReservedRequestService service =
                new ModelRouteReservedRequestService(
                        decisions,
                        bindings,
                        scenario.normalizer(),
                        ModelTestFixtures.CLOCK
                );

        assertThatThrownBy(
                () -> service.seal(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.clientRequestId(),
                        scenario.reserved(),
                        scenario.knowledgeBaseId(),
                        scenario.knowledgeMode()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "not sealed"
                );
    }

    @Test
    void requireSameAcceptsExactlyMatchingPersistedSeal() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteRequestBindingRepository bindings =
                mock(
                        ModelRouteRequestBindingRepository.class
                );

        String expectedBaseSha256 =
                ModelRouteExecutionIdentity.baseSha256(
                        scenario.reserved()
                );

        when(
                bindings.findReservedHash(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId()
                )
        ).thenReturn(
                Optional.of(
                        expectedBaseSha256
                )
        );

        ModelRouteReservedRequestService service =
                new ModelRouteReservedRequestService(
                        mock(
                                ModelRouteDecisionRepository.class
                        ),
                        bindings,
                        scenario.normalizer(),
                        ModelTestFixtures.CLOCK
                );

        assertThatCode(
                () -> service.requireSame(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId(),
                        scenario.reserved()
                )
        ).doesNotThrowAnyException();

        verify(
                bindings
        ).findReservedHash(
                scenario.decision().id(),
                scenario.turnId(),
                scenario.providerOperationId()
        );
    }

    @Test
    void requireSameRejectsMissingPersistedSeal() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteRequestBindingRepository bindings =
                mock(
                        ModelRouteRequestBindingRepository.class
                );

        when(
                bindings.findReservedHash(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId()
                )
        ).thenReturn(
                Optional.empty()
        );

        ModelRouteReservedRequestService service =
                new ModelRouteReservedRequestService(
                        mock(
                                ModelRouteDecisionRepository.class
                        ),
                        bindings,
                        scenario.normalizer(),
                        ModelTestFixtures.CLOCK
                );

        assertThatThrownBy(
                () -> service.requireSame(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId(),
                        scenario.reserved()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "seal is missing"
                );
    }

    @Test
    void requireSameRejectsChangedPersistedSeal() {
        var scenario =
                ModelExecutionBindingTestSupport.general();

        ModelRouteRequestBindingRepository bindings =
                mock(
                        ModelRouteRequestBindingRepository.class
                );

        when(
                bindings.findReservedHash(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId()
                )
        ).thenReturn(
                Optional.of(
                        "b".repeat(64)
                )
        );

        ModelRouteReservedRequestService service =
                new ModelRouteReservedRequestService(
                        mock(
                                ModelRouteDecisionRepository.class
                        ),
                        bindings,
                        scenario.normalizer(),
                        ModelTestFixtures.CLOCK
                );

        assertThatThrownBy(
                () -> service.requireSame(
                        scenario.decision().id(),
                        scenario.turnId(),
                        scenario.providerOperationId(),
                        scenario.reserved()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "differs from persisted request seal"
                );
    }
}
