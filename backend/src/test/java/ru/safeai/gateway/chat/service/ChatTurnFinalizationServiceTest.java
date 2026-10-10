package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.exception.ChatStaleProcessorException;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.knowledge.rag.AnswerPassportService;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnFinalizationServiceTest {

    @Mock ChatTurnRepository turnRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatQuotaService quotaService;
    @Mock AuditEventService auditEventService;
    @Mock ChatMetrics metrics;
    @Mock AnswerPassportService answerPassportService;

    private ChatTurnFinalizationService service;
    private ChatProcessingContext context;
    private ChatSessionEntity session;
    private ChatTurnEntity processingTurn;
    private ChatMessageEntity userMessage;

    @BeforeEach
    void setUp() {
        service = new ChatTurnFinalizationService(
                turnRepository,
                messageRepository,
                sessionRepository,
                quotaService,
                auditEventService,
                new ChatMapper(),
                metrics,
                ChatTestFixtures.CLOCK,
                answerPassportService
        );
        context = context();
        session = ChatTestFixtures.session();

        assertThat(session)
                .as("ChatTestFixtures.session() не должен возвращать null")
                .isNotNull();
        assertThat(session.getOrganization())
                .as("У тестовой chat session должна быть organization")
                .isNotNull();

        userMessage = ChatMessageEntity.user(
                session,
                "Question",
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.NOW.minusSeconds(10)
        );
        userMessage.setId(ChatTestFixtures.USER_MESSAGE_ID);
    }

    /**
     * V72: provider_call_started_at is no longer written by finalization.
     * Only the durable ModelExecutionAttempt STARTED boundary may set it.
     * If execution is rejected before STARTED, finalization must instead
     * complete the turn through the dedicated pre-provider FAILED transition.
     * <p>
     * This is a service-level test; PostgreSQL STARTED/organization-epoch
     * serialization is verified separately by the physical-attempt tests.
     */
    @Test
    void preProviderFailureUsesDedicatedFencedTransitionAndRequiredEvidence() {
        String providerErrorType = "ORGANIZATION_AUTH_EPOCH_REVOKED";
        String failureCode = "AI_ORGANIZATION_AUTH_EPOCH_REVOKED";

        when(turnRepository.markFailedBeforeProviderCall(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                providerErrorType,
                failureCode,
                ChatTestFixtures.NOW
        )).thenReturn(1);

        service.failBeforeProviderCall(
                context,
                providerErrorType,
                failureCode,
                ChatTestFixtures.principal()
        );

        // A successful fenced terminal transition must precede quota release,
        // audit persistence and terminal metrics reporting.
        InOrder persistenceOrder = inOrder(
                turnRepository,
                quotaService,
                auditEventService,
                metrics
        );
        persistenceOrder.verify(turnRepository).markFailedBeforeProviderCall(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                providerErrorType,
                failureCode,
                ChatTestFixtures.NOW
        );
        persistenceOrder.verify(quotaService).releaseFailure(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.NOW
        );
        persistenceOrder.verify(auditEventService).recordPrincipalSnapshot(
                argThat((SafeAiUserPrincipal actor) -> actor != null
                        && ChatTestFixtures.USER_ID.equals(actor.getId())
                        && ChatTestFixtures.ORGANIZATION_ID.equals(actor.getOrganizationId())),
                eq(ChatTestFixtures.ORGANIZATION_ID),
                eq(AuditEventType.AI_RESPONSE_FAILED),
                argThat((AuditDetails details) -> details != null
                        && ChatTestFixtures.TURN_ID.equals(details.toMap().get("turnId"))
                        && "FAILED".equals(details.toMap().get("turnState"))
                        && providerErrorType.equals(details.toMap().get("providerErrorType"))
                        && failureCode.equals(details.toMap().get("failureCode"))
                        && Boolean.FALSE.equals(details.toMap().get("outcomeAmbiguous")))
        );
        persistenceOrder.verify(metrics).recordTerminalAfterCommit(ChatTurnState.FAILED);

        // A pre-provider failure must never insert an assistant response
        // or persist a post-response Answer Passport.
        verifyNoInteractions(messageRepository, sessionRepository, answerPassportService);
    }

    /**
     * If the fencing token / PROCESSING state no longer matches, the SQL
     * transition updates zero rows. No secondary state or audit evidence may
     * be produced by a stale worker. The physical STARTED boundary itself is
     * intentionally not exposed through ChatTurnFinalizationService.
     */
    @Test
    void stalePreProviderFailureIsFencedWithoutQuotaOrAuditSideEffects() {
        String providerErrorType = "ORGANIZATION_AUTH_EPOCH_REVOKED";
        String failureCode = "AI_ORGANIZATION_AUTH_EPOCH_REVOKED";

        when(turnRepository.markFailedBeforeProviderCall(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                providerErrorType,
                failureCode,
                ChatTestFixtures.NOW
        )).thenReturn(0);

        assertThatThrownBy(() -> service.failBeforeProviderCall(
                context,
                providerErrorType,
                failureCode,
                ChatTestFixtures.principal()
        )).isInstanceOf(ChatStaleProcessorException.class);

        verify(turnRepository).markFailedBeforeProviderCall(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                providerErrorType,
                failureCode,
                ChatTestFixtures.NOW
        );
        verifyNoInteractions(
                quotaService,
                auditEventService,
                metrics,
                messageRepository,
                sessionRepository,
                answerPassportService
        );
    }

    /**
     * Regression guard: the deleted entry points must not return. Only a
     * physical attempt admitted by PostgreSQL V72 may record STARTED.
     */
    @Test
    void physicalStartCannotBeMarkedThroughFinalizationOrTurnRepository() {
        assertThat(Arrays.stream(ChatTurnFinalizationService.class.getMethods())
                .map(Method::getName))
                .doesNotContain("markProviderCallStarted");

        assertThat(Arrays.stream(ChatTurnRepository.class.getMethods())
                .map(Method::getName))
                .doesNotContain("markProviderCallStarted");
    }

    @Test
    void successConditionallyTransitionsTurnBeforeAssistantInsert() {
        stubOwnedTurn();
        when(turnRepository.markSucceeded(
                eq(ChatTestFixtures.TURN_ID),
                eq(ChatTestFixtures.PROCESSING_TOKEN),
                any(UUID.class),
                eq("requested-model"),
                eq("resolved-model"),
                eq("provider-request-id"),
                eq(ChatTestFixtures.NOW)
        )).thenReturn(1);
        when(messageRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findOwnedMessage(
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(userMessage));
        when(sessionRepository.getReferenceById(ChatTestFixtures.CHAT_ID))
                .thenReturn(session);
        when(sessionRepository.touchOwned(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.NOW
        )).thenReturn(1);

        SendMessageResponse result = service.succeed(
                context,
                ChatTestFixtures.pricedResponse(),
                ChatTestFixtures.principal()
        );

        ArgumentCaptor<ChatMessageEntity> assistantCaptor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        InOrder persistenceOrder = inOrder(turnRepository, messageRepository);
        persistenceOrder.verify(turnRepository).markSucceeded(
                eq(ChatTestFixtures.TURN_ID),
                eq(ChatTestFixtures.PROCESSING_TOKEN),
                any(UUID.class),
                eq("requested-model"),
                eq("resolved-model"),
                eq("provider-request-id"),
                eq(ChatTestFixtures.NOW)
        );
        persistenceOrder.verify(messageRepository).saveAndFlush(any());

        verify(sessionRepository).getReferenceById(ChatTestFixtures.CHAT_ID);
        verify(messageRepository).saveAndFlush(assistantCaptor.capture());
        ChatMessageEntity assistant = assistantCaptor.getValue();

        assertThat(assistant.getReplyToMessageId())
                .isEqualTo(ChatTestFixtures.USER_MESSAGE_ID);
        assertThat(assistant.getRequestedModel()).isEqualTo("requested-model");
        assertThat(assistant.getModel()).isEqualTo("resolved-model");
        assertThat(assistant.getProviderRequestId()).isEqualTo("provider-request-id");
        assertThat(result.state()).isEqualTo("SUCCEEDED");
        assertThat(result.replay()).isFalse();
        assertThat(result.assistantMessage().aiResponseStatus()).isEqualTo("COMPLETED");

        verify(answerPassportService).persist(
                eq(context),
                eq(assistant.getId()),
                eq("openai"),
                any(),
                any(SafeAiUserPrincipal.class),
                eq(ChatTestFixtures.NOW)
        );
        verify(quotaService).settleSuccess(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.pricedResponse(),
                ChatTestFixtures.NOW
        );
        verify(metrics).recordTerminalAfterCommit(
                ChatTurnState.SUCCEEDED,
                processingTurn.getCreatedAt(),
                ChatTestFixtures.NOW
        );
    }

    @Test
    void lostFencingTokenPreventsSecondAssistantReply() {
        stubOwnedTurn();
        when(turnRepository.markSucceeded(
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(0);

        assertThatThrownBy(() -> service.succeed(
                context,
                ChatTestFixtures.freeResponse(),
                ChatTestFixtures.principal()
        )).isInstanceOf(ChatStaleProcessorException.class);

        verify(messageRepository, never()).saveAndFlush(any());
        verify(answerPassportService, never()).persist(
                any(), any(), any(), any(), any(), any()
        );
        verify(quotaService, never()).settleSuccess(any(), any(), any());
    }

    @Test
    void connectFailureBecomesStableFailedAndReleasesQuota() {
        AiProviderException exception = unambiguousConnectionFailure();

        when(turnRepository.markFailed(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                "openai",
                "requested-model",
                null,
                "CONNECT_FAILURE",
                "AI_PROVIDER_CONNECT_FAILURE",
                ChatTestFixtures.NOW
        )).thenReturn(1);

        service.failUnambiguous(
                context,
                exception,
                ChatTestFixtures.principal()
        );

        verify(turnRepository).markFailed(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                "openai",
                "requested-model",
                null,
                "CONNECT_FAILURE",
                "AI_PROVIDER_CONNECT_FAILURE",
                ChatTestFixtures.NOW
        );
        verify(quotaService).releaseFailure(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.NOW
        );
        verify(metrics).recordTerminalAfterCommit(ChatTurnState.FAILED);
    }

    @Test
    void preProviderFailureDoesNotBecomeAmbiguous() {
        when(turnRepository.markFailedBeforeProviderCall(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                "LEASE_WATCHDOG_UNAVAILABLE",
                "CHAT_LEASE_WATCHDOG_UNAVAILABLE",
                ChatTestFixtures.NOW
        )).thenReturn(1);

        service.failBeforeProviderCall(
                context,
                "LEASE_WATCHDOG_UNAVAILABLE",
                "CHAT_LEASE_WATCHDOG_UNAVAILABLE",
                ChatTestFixtures.principal()
        );

        verify(quotaService).releaseFailure(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.NOW
        );
        verify(metrics).recordTerminalAfterCommit(ChatTurnState.FAILED);
        verify(quotaService, never()).markAmbiguous(any(), any());
    }

    @Test
    void ambiguousOutcomeKeepsQuotaReservationConservative() {
        when(turnRepository.markAmbiguous(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                "openai",
                "requested-model",
                "provider-request-id",
                "TIMEOUT",
                "AI_PROVIDER_OUTCOME_AMBIGUOUS",
                ChatTestFixtures.NOW
        )).thenReturn(1);

        service.markAmbiguous(
                context,
                "openai",
                "requested-model",
                "provider-request-id",
                "TIMEOUT",
                "AI_PROVIDER_OUTCOME_AMBIGUOUS",
                ChatTestFixtures.principal()
        );

        verify(quotaService).markAmbiguous(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.NOW
        );
        verify(metrics).recordTerminalAfterCommit(ChatTurnState.AMBIGUOUS);
    }

    @Test
    void replayReadsExactlyPersistedUserAndAssistantMessages() {
        ChatTurnEntity succeeded = processingTurn();
        succeeded.setState(ChatTurnState.SUCCEEDED);
        succeeded.setProcessingToken(null);
        succeeded.setLeaseUntil(null);
        succeeded.setProviderCallStartedAt(ChatTestFixtures.NOW.minusSeconds(5));
        succeeded.setAssistantMessageId(ChatTestFixtures.ASSISTANT_MESSAGE_ID);
        succeeded.setCompletedAt(ChatTestFixtures.NOW);
        succeeded.setUpdatedAt(ChatTestFixtures.NOW);

        when(turnRepository.findByIdAndSession_IdAndUser_IdAndOrganization_Id(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(succeeded));

        ChatMessageEntity assistant = ChatMessageEntity.completedAssistant(
                ChatTestFixtures.ASSISTANT_MESSAGE_ID,
                ChatTestFixtures.session(),
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.freeResponse(),
                ChatTestFixtures.NOW
        );
        when(messageRepository.findOwnedMessage(
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(userMessage));
        when(messageRepository.findOwnedMessage(
                ChatTestFixtures.ASSISTANT_MESSAGE_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(assistant));

        SendMessageResponse response = service.replaySucceeded(
                replayContext(),
                ChatTestFixtures.principal()
        );

        assertThat(response.replay()).isTrue();
        assertThat(response.userMessage().clientRequestId())
                .isEqualTo(ChatTestFixtures.CLIENT_REQUEST_ID);
        assertThat(response.assistantMessage().replyToMessageId())
                .isEqualTo(ChatTestFixtures.USER_MESSAGE_ID);
        verify(answerPassportService).findByTurn(
                eq(ChatTestFixtures.TURN_ID),
                any(SafeAiUserPrincipal.class)
        );
    }

    private void stubOwnedTurn() {
        processingTurn = mock(ChatTurnEntity.class);
        when(processingTurn.getState()).thenReturn(ChatTurnState.PROCESSING);
        when(processingTurn.getProvider()).thenReturn("openai");
        when(processingTurn.getCreatedAt())
                .thenReturn(ChatTestFixtures.NOW.minusSeconds(10));
        when(turnRepository.findByIdAndSession_IdAndUser_IdAndOrganization_Id(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(processingTurn));
    }

    private static ChatProcessingContext context() {
        return ChatProcessingContextTestFixtures.processing(
                new ru.safeai.gateway.ai.dto.AiChatRequest(
                        ChatTestFixtures.USER_ID,
                        ChatTestFixtures.ORGANIZATION_ID,
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.PROVIDER_OPERATION_ID,
                        null,
                        null,
                        "Question",
                        java.util.List.of()
                )
        );
    }

    private static ChatProcessingContext replayContext() {
        return ChatProcessingContextTestFixtures.replay();
    }

    private static ChatTurnEntity processingTurn() {
        return ChatTurnEntity.processing(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.session(),
                ChatTestFixtures.CLIENT_REQUEST_ID,
                "0".repeat(64),
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                ChatTestFixtures.NOW.minusSeconds(10),
                ChatTestFixtures.NOW.plusSeconds(60),
                "openai",
                "requested-model",
                ChatProcessingContextTestFixtures.MODEL_ROUTE_DECISION_ID,
                null,
                KnowledgeMode.GENERAL
        );
    }

    private static AiProviderException unambiguousConnectionFailure() {
        return new AiProviderUnavailableException(
                "openai",
                "requested-model",
                true,
                false,
                "provider connection failure",
                null
        );
    }
}
