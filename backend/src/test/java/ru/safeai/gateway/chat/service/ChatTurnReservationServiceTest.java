package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatQuotaExceededException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.chat.exception.ChatTurnInProgressException;
import ru.safeai.gateway.chat.exception.IdempotencyKeyReusedException;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatHistoryRepository;
import ru.safeai.gateway.chat.repository.ChatHistoryTurn;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.repository.ChatTurnMutexRepository;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelRouteResult;
import ru.safeai.gateway.model.service.ModelRoutingEnvelopeService;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnReservationServiceTest {

    private static final UUID MODEL_ROUTE_DECISION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    private static final UUID MODEL_CATALOG_ENTRY_ID =
            UUID.fromString("88888888-8888-4888-8888-888888888888");

    private static final UUID MODEL_POLICY_ID =
            UUID.fromString("99999999-9999-4999-8999-999999999999");

    private static final long ROUTING_ENVELOPE_TOKENS =
            4_096L;

    @Mock
    ChatSessionRepository sessionRepository;

    @Mock
    ChatMessageRepository messageRepository;

    @Mock
    ChatTurnRepository turnRepository;

    @Mock
    ChatTurnMutexRepository mutexRepository;

    @Mock
    ChatHistoryRepository historyRepository;

    @Mock
    RedisRateLimitService rateLimitService;

    @Mock
    ChatQuotaService quotaService;

    @Mock
    ChatTurnRecoveryCoordinator recoveryCoordinator;

    @Mock
    AuditEventService auditEventService;

    @Mock
    ModelRoutingService modelRoutingService;

    @Mock
    ModelRoutingEnvelopeService routingEnvelopeService;

    @Mock
    ChatMetrics metrics;

    private ChatTurnReservationService service;
    private ChatContentNormalizer normalizer;
    private ChatProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties(
                50,
                50,
                100,
                100,
                16_000,
                Duration.ofMinutes(3),
                4,
                1000
        );

        normalizer =
                new ChatContentNormalizer(
                        properties
                );

        service =
                new ChatTurnReservationService(
                        sessionRepository,
                        messageRepository,
                        turnRepository,
                        mutexRepository,
                        historyRepository,
                        new AiHistoryBuilder(),
                        normalizer,
                        rateLimitService,
                        quotaService,
                        recoveryCoordinator,
                        auditEventService,
                        modelRoutingService,
                        routingEnvelopeService,
                        properties,
                        metrics,
                        ChatTestFixtures.CLOCK
                );
    }

    @Test
    void succeededReplayDoesNotConsumeRateLimitOrQuota() {
        ChatTurnEntity turn =
                existingTurn(
                        ChatTurnState.SUCCEEDED,
                        "Hello"
                );

        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(turn)
        );

        ChatProcessingContext result =
                service.reserveOrReplay(
                        ChatTestFixtures.CHAT_ID,
                        request("Hello"),
                        ChatTestFixtures.principal()
                );

        assertThat(result.replay())
                .isTrue();

        assertThat(result.aiRequest())
                .isNull();

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                historyRepository,
                routingEnvelopeService,
                modelRoutingService
        );

        verify(metrics)
                .recordReplay(
                        ChatTurnState.SUCCEEDED
                );

        verify(
                messageRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void reusedIdWithDifferentNormalizedContentReturnsConflict() {
        ChatTurnEntity turn =
                existingTurn(
                        ChatTurnState.SUCCEEDED,
                        "first"
                );

        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(turn)
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("second"),
                                ChatTestFixtures.principal()
                        )
        )
                .isInstanceOf(
                        IdempotencyKeyReusedException.class
                )
                .hasMessageContaining(
                        "clientRequestId"
                );

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                routingEnvelopeService,
                modelRoutingService
        );
    }

    @Test
    void failedReplayReturnsStableFailureWithoutNewProviderReservation() {
        ChatTurnEntity turn =
                existingTurn(
                        ChatTurnState.FAILED,
                        "Hello"
                );

        turn.setFailureCode(
                "AI_PROVIDER_INVALID_REQUEST"
        );

        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(turn)
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        )
                .isInstanceOf(
                        ChatTurnFailedException.class
                )
                .extracting("code")
                .isEqualTo(
                        "AI_PROVIDER_INVALID_REQUEST"
                );

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                routingEnvelopeService,
                modelRoutingService
        );
    }

    @Test
    void activeProcessingReplayReturnsInProgressWithRetryAfter() {
        ChatTurnEntity turn =
                existingTurn(
                        ChatTurnState.PROCESSING,
                        "Hello"
                );

        turn.setLeaseUntil(
                ChatTestFixtures.NOW.plusSeconds(
                        30
                )
        );

        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(turn)
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        )
                .isInstanceOf(
                        ChatTurnInProgressException.class
                )
                .satisfies(
                        exception ->
                                assertThat(
                                        (
                                                (
                                                        ChatTurnInProgressException
                                                ) exception
                                        ).getRetryAfter()
                                ).isEqualTo(
                                        Duration.ofSeconds(
                                                30
                                        )
                                )
                );

        verifyNoInteractions(
                routingEnvelopeService,
                modelRoutingService
        );
    }

    @Test
    void staleProcessingBeforeProviderCallBecomesFailedNotAmbiguous() {
        ChatTurnEntity turn =
                existingTurn(
                        ChatTurnState.PROCESSING,
                        "Hello"
                );

        turn.setLeaseUntil(
                ChatTestFixtures.NOW.minusSeconds(
                        1
                )
        );

        turn.setProviderCallStartedAt(
                null
        );

        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(turn)
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        )
                .isInstanceOf(
                        ChatTurnFailedException.class
                )
                .extracting("code")
                .isEqualTo(
                        "STALE_BEFORE_PROVIDER_CALL"
                );

        verify(
                recoveryCoordinator
        ).recoverInline(
                turn,
                ChatTestFixtures.NOW
        );

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                routingEnvelopeService,
                modelRoutingService
        );
    }

    @Test
    void staleProcessingAfterProviderCallBecomesAmbiguous() {
        ChatTurnEntity turn =
                existingTurn(
                        ChatTurnState.PROCESSING,
                        "Hello"
                );

        turn.setLeaseUntil(
                ChatTestFixtures.NOW.minusSeconds(
                        1
                )
        );

        turn.setProviderCallStartedAt(
                ChatTestFixtures.NOW.minusSeconds(
                        10
                )
        );

        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(turn)
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        ).isInstanceOf(
                AiOutcomeAmbiguousException.class
        );

        verify(
                recoveryCoordinator
        ).recoverInline(
                turn,
                ChatTestFixtures.NOW
        );

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                routingEnvelopeService,
                modelRoutingService
        );
    }

    @Test
    void newTurnPersistsProviderOperationIdBeforeReturningAiRequest() {
        stubNewTurn();

        ArgumentCaptor<ChatTurnEntity> turnCaptor =
                ArgumentCaptor.forClass(
                        ChatTurnEntity.class
                );

        ChatProcessingContext result =
                service.reserveOrReplay(
                        ChatTestFixtures.CHAT_ID,
                        request(
                                "Hello\r\nworld"
                        ),
                        ChatTestFixtures.principal()
                );

        verify(
                turnRepository
        ).saveAndFlush(
                turnCaptor.capture()
        );

        verify(metrics)
                .recordReservedAfterCommit();

        ChatTurnEntity persisted =
                turnCaptor.getValue();

        assertThat(
                persisted.getProviderOperationId()
        )
                .isEqualTo(
                        result.providerOperationId()
                )
                .isEqualTo(
                        result.aiRequest()
                                .providerOperationId()
                );

        assertThat(
                result.aiRequest()
                        .userMessage()
        ).isEqualTo(
                "Hello\nworld"
        );

        assertThat(
                result.processingToken()
        ).isNotNull();

        assertThat(
                result.modelRouteDecisionId()
        ).isEqualTo(
                MODEL_ROUTE_DECISION_ID
        );

        assertThat(
                result.aiRequest()
                        .reservedInputTokens()
        ).isEqualTo(
                64L
        );

        assertThat(
                result.aiRequest()
                        .maxOutputTokens()
        ).isEqualTo(
                256
        );

        assertThat(
                persisted.getModelRouteDecisionId()
        ).isEqualTo(
                MODEL_ROUTE_DECISION_ID
        );

        assertThat(
                persisted.getProvider()
        ).isEqualTo(
                "openai"
        );

        assertThat(
                persisted.getRequestedModel()
        ).isEqualTo(
                "gpt-safeai-test"
        );
    }

    @Test
    void newTurnAppliesRouteQuotaAndRateInDeterministicOrder() {
        stubNewTurn();

        ChatProcessingContext result =
                service.reserveOrReplay(
                        ChatTestFixtures.CHAT_ID,
                        request("Hello"),
                        ChatTestFixtures.principal()
                );

        InOrder order =
                inOrder(
                        mutexRepository,
                        routingEnvelopeService,
                        modelRoutingService,
                        messageRepository,
                        turnRepository,
                        quotaService,
                        rateLimitService
                );

        order.verify(
                mutexRepository
        ).lockChat(
                ChatTestFixtures.CHAT_ID
        );

        order.verify(
                routingEnvelopeService
        ).additionalInputTokenUpperBound(
                eq(false),
                any()
        );

        order.verify(
                modelRoutingService
        ).decide(
                any(ModelRouteRequest.class),
                any(SafeAiUserPrincipal.class)
        );

        order.verify(
                messageRepository
        ).saveAndFlush(
                any()
        );

        order.verify(
                turnRepository
        ).saveAndFlush(
                any()
        );

        order.verify(
                quotaService
        ).reserve(
                eq(
                        ChatTestFixtures.CHAT_ID
                ),
                eq(
                        result.turnId()
                ),
                eq(
                        ChatTestFixtures.CLIENT_REQUEST_ID
                ),
                eq(
                        ChatTestFixtures.ORGANIZATION_ID
                ),
                eq(
                        ChatTestFixtures.USER_ID
                )
        );

        order.verify(
                modelRoutingService
        ).validateAllowedTurnLinkBeforeExternalSideEffects();

        order.verify(
                rateLimitService
        ).checkAiMessageAllowed(
                any(
                        SafeAiUserPrincipal.class
                )
        );
    }

    @Test
    void malformedSucceededHistoryDoesNotCreateTurnOrConsumeLimits() {
        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                turnRepository.findSessionTurnByStateForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTurnState.PROCESSING
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                historyRepository.findNewestSucceededTurns(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.ORGANIZATION_ID,
                        ChatTestFixtures.USER_ID,
                        properties.historyTurnLimit()
                )
        ).thenReturn(
                List.of(
                        new ChatHistoryTurn(
                                UUID.randomUUID(),
                                "Question",
                                null
                        )
                )
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "USER/ASSISTANT"
                );

        verify(
                messageRepository,
                never()
        ).saveAndFlush(
                any()
        );

        verify(
                turnRepository,
                never()
        ).saveAndFlush(
                any()
        );

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                routingEnvelopeService,
                modelRoutingService
        );
    }

    @Test
    void quotaRejectionOccursBeforeRateLimitConsumption() {
        stubNewTurn();

        org.mockito.Mockito.doThrow(
                new ChatQuotaExceededException(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.TURN_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID,
                        "user.monthlyRequests"
                )
        ).when(
                quotaService
        ).reserve(
                eq(
                        ChatTestFixtures.CHAT_ID
                ),
                any(
                        UUID.class
                ),
                eq(
                        ChatTestFixtures.CLIENT_REQUEST_ID
                ),
                eq(
                        ChatTestFixtures.ORGANIZATION_ID
                ),
                eq(
                        ChatTestFixtures.USER_ID
                )
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        ).isInstanceOf(
                ChatQuotaExceededException.class
        );

        verify(
                rateLimitService,
                never()
        ).checkAiMessageAllowed(
                any()
        );

        verify(
                modelRoutingService
        ).decide(
                any(ModelRouteRequest.class),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void differentActiveTurnBlocksParallelProviderOperationInSameChat() {
        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.empty()
        );

        ChatTurnEntity active =
                existingTurn(
                        ChatTurnState.PROCESSING,
                        "other"
                );

        active.setClientRequestId(
                UUID.randomUUID()
        );

        active.setLeaseUntil(
                ChatTestFixtures.NOW.plusSeconds(
                        60
                )
        );

        when(
                turnRepository.findSessionTurnByStateForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTurnState.PROCESSING
                )
        ).thenReturn(
                Optional.of(active)
        );

        assertThatThrownBy(
                () ->
                        service.reserveOrReplay(
                                ChatTestFixtures.CHAT_ID,
                                request("Hello"),
                                ChatTestFixtures.principal()
                        )
        ).isInstanceOf(
                ChatBusyException.class
        );

        verifyNoInteractions(
                rateLimitService,
                quotaService,
                routingEnvelopeService,
                modelRoutingService
        );
    }

    private void stubNewTurn() {
        stubOwnedSession();

        when(
                turnRepository.findByIdempotencyKeyForUpdate(
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                turnRepository.findSessionTurnByStateForUpdate(
                        ChatTestFixtures.CHAT_ID,
                        ChatTurnState.PROCESSING
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                messageRepository.saveAndFlush(
                        any()
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(
                                0
                        )
        );

        when(
                turnRepository.saveAndFlush(
                        any()
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(
                                0
                        )
        );

        when(
                historyRepository.findNewestSucceededTurns(
                        any(
                                UUID.class
                        ),
                        any(
                                UUID.class
                        ),
                        any(
                                UUID.class
                        ),
                        anyInt()
                )
        ).thenReturn(
                List.of()
        );

        when(
                routingEnvelopeService.additionalInputTokenUpperBound(
                        anyBoolean(),
                        any()
                )
        ).thenReturn(
                ROUTING_ENVELOPE_TOKENS
        );

        when(
                modelRoutingService.decide(
                        any(
                                ModelRouteRequest.class
                        ),
                        any(
                                SafeAiUserPrincipal.class
                        )
                )
        ).thenReturn(
                allowedRoute()
        );
    }

    private ModelRouteResult allowedRoute() {
        return new ModelRouteResult(
                MODEL_ROUTE_DECISION_ID,
                MODEL_CATALOG_ENTRY_ID,
                1,
                MODEL_POLICY_ID,
                1,
                "safeai-test",
                "openai",
                "gpt-safeai-test",
                64,
                256,
                new BigDecimal(
                        "0.001000"
                ),
                true,
                false,
                ModelRouteReason.POLICY_DEFAULT
        );
    }

    private void stubOwnedSession() {
        when(
                sessionRepository
                        .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                                ChatTestFixtures.CHAT_ID,
                                ChatTestFixtures.USER_ID,
                                ChatTestFixtures.ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        ChatTestFixtures.session()
                )
        );
    }

    private SendMessageRequest request(
            String content
    ) {
        return new SendMessageRequest(
                content,
                ChatTestFixtures.CLIENT_REQUEST_ID
        );
    }

    private ChatTurnEntity existingTurn(
            ChatTurnState state,
            String content
    ) {
        ChatTurnEntity turn =
                ChatTurnEntity.processing(
                        ChatTestFixtures.TURN_ID,
                        ChatTestFixtures.session(),
                        ChatTestFixtures.CLIENT_REQUEST_ID,
                        normalizer.requestHash(
                                normalizer.normalize(
                                        content
                                ),
                                null,
                                KnowledgeMode.GENERAL
                        ),
                        ChatTestFixtures.PROVIDER_OPERATION_ID,
                        ChatTestFixtures.USER_MESSAGE_ID,
                        ChatTestFixtures.PROCESSING_TOKEN,
                        ChatTestFixtures.NOW.minusSeconds(
                                10
                        ),
                        ChatTestFixtures.NOW.plusSeconds(
                                60
                        ),
                        "openai"
                );

        turn.setState(
                state
        );

        if (
                state
                        != ChatTurnState.PROCESSING
        ) {
            turn.setProcessingToken(
                    null
            );

            turn.setLeaseUntil(
                    null
            );

            turn.setCompletedAt(
                    ChatTestFixtures.NOW
            );
        }

        if (
                state
                        == ChatTurnState.SUCCEEDED
        ) {
            turn.setAssistantMessageId(
                    ChatTestFixtures.ASSISTANT_MESSAGE_ID
            );

            turn.setProviderCallStartedAt(
                    ChatTestFixtures.NOW.minusSeconds(
                            5
                    )
            );
        }

        if (
                state
                        == ChatTurnState.AMBIGUOUS
        ) {
            turn.setOutcomeAmbiguous(
                    true
            );

            turn.setProviderCallStartedAt(
                    ChatTestFixtures.NOW.minusSeconds(
                            5
                    )
            );
        }

        return turn;
    }
}
