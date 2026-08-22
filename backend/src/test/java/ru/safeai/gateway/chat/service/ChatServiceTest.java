package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatAccessRevokedException;
import ru.safeai.gateway.chat.exception.ChatStaleProcessorException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagService;
import ru.safeai.gateway.knowledge.rag.RagCompletion;
import ru.safeai.gateway.knowledge.rag.RagPreparation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock UserRepository userRepository;
    @Mock AiProvider aiProvider;
    @Mock AuditEventService auditEventService;
    @Mock ChatTurnReservationService reservationService;
    @Mock ChatTurnFinalizationService finalizationService;
    @Mock ChatSecurityStateService securityStateService;
    @Mock ChatLockService lockService;
    @Mock ChatTurnLeaseService leaseService;
    @Mock KnowledgeRagService ragService;

    private ChatService service;
    private ChatLockService.ChatLock redisLock;
    private ChatTurnLeaseService.LeaseWatch leaseWatch;
    private ChatProcessingContext processing;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties(
                50, 50, 100, 100, 16_000,
                Duration.ofMinutes(3),
                4,
                1000
        );
        service = new ChatService(
                sessionRepository,
                messageRepository,
                userRepository,
                aiProvider,
                auditEventService,
                reservationService,
                finalizationService,
                securityStateService,
                new ChatMapper(),
                lockService,
                leaseService,
                properties,
                ChatTestFixtures.CLOCK,
                ragService
        );
        redisLock = new ChatLockService.ChatLock(
                ChatTestFixtures.CHAT_ID,
                "lock-key",
                "owner",
                new AtomicBoolean(true),
                mock(ScheduledFuture.class),
                new AtomicBoolean(false)
        );
        leaseWatch = new ChatTurnLeaseService.LeaseWatch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                new AtomicBoolean(true),
                mock(ScheduledFuture.class),
                new AtomicBoolean(false)
        );
        processing = processingContext();
        org.mockito.Mockito.lenient().when(ragService.prepare(any(), any()))
                .thenAnswer(invocation -> RagPreparation.general(
                        invocation.<ChatProcessingContext>getArgument(0)
                                .aiRequest()
                ));
        org.mockito.Mockito.lenient().when(ragService.complete(any(), any()))
                .thenAnswer(invocation -> RagCompletion.general(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
    }

    @Test
    void providerOperationMarkerIsCommittedBeforeProviderInvocation() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());
        SendMessageResponse expected = successResponse(false);
        when(finalizationService.succeedRag(
                eq(processing),
                any(RagCompletion.class),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenReturn(expected);

        SendMessageResponse result = service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        );

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(finalizationService, aiProvider);
        order.verify(finalizationService).markProviderCallStarted(processing);
        order.verify(aiProvider).sendMessage(processing.aiRequest());
        verify(lockService).ensureValid(redisLock);
        verify(leaseService).ensureValid(leaseWatch);
        verify(securityStateService).assertStillActive(
                eq(ChatTestFixtures.CHAT_ID),
                eq(ChatTestFixtures.TURN_ID),
                eq(ChatTestFixtures.CLIENT_REQUEST_ID),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
    }

    @Test
    void succeededReplaySkipsRateProviderLeaseAndQuotaPath() {
        stubOwnedChat();
        ChatProcessingContext replay = replayContext();
        when(reservationService.reserveOrReplay(
                eq(ChatTestFixtures.CHAT_ID),
                eq(request()),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenReturn(replay);
        when(finalizationService.replaySucceeded(
                eq(replay),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenReturn(successResponse(true));

        SendMessageResponse result = service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        );

        assertThat(result.replay()).isTrue();
        verify(aiProvider, never()).sendMessage(any());
        verify(leaseService, never()).watch(any(), any(), any(), any());
        verify(finalizationService, never()).markProviderCallStarted(any());
    }

    @Test
    void unambiguousProviderFailureBecomesStableFailedTurn() {
        stubOwnedChatAndProcessing();
        AiProviderException providerException = providerException(false);
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenThrow(providerException);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(ChatTurnFailedException.class)
                .extracting("code")
                .isEqualTo("AI_PROVIDER_INVALID_REQUEST");

        verify(finalizationService).failUnambiguous(
                eq(processing),
                eq(providerException),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).markAmbiguous(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void readTimeoutWithAmbiguousOutcomeIsNeverAutomaticallyRetried() {
        stubOwnedChatAndProcessing();
        AiProviderException providerException = providerException(true);
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenThrow(providerException);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(aiProvider).sendMessage(processing.aiRequest());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                eq("requested-model"),
                eq("provider-request-id"),
                eq(AiProviderErrorType.TIMEOUT.name()),
                eq("AI_PROVIDER_OUTCOME_AMBIGUOUS"),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).failUnambiguous(any(), any(), any());
    }

    @Test
    void unknownRuntimeAfterProviderStartIsClassifiedConservatively() {
        stubOwnedChatAndProcessing();
        RuntimeException exception = new RuntimeException("socket reset");
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenThrow(exception);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq("RuntimeException"),
                eq("AI_PROVIDER_UNCLASSIFIED_OUTCOME"),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
    }

    @Test
    void lostRedisOwnershipAfterResponseCannotPersistAssistant() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());
        doThrow(new ChatLockUnavailableException("lost", null))
                .when(lockService)
                .ensureValid(redisLock);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(finalizationService, never()).succeed(any(), any(), any());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                eq("requested-model"),
                eq("provider-request-id"),
                eq("PROCESSING_OWNERSHIP_LOST"),
                eq("CHAT_PROCESSING_OWNERSHIP_LOST"),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
    }

    @Test
    void staleDbLeaseAfterResponseCannotPersistAssistant() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());
        doThrow(new ChatStaleProcessorException(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID
        )).when(leaseService).ensureValid(leaseWatch);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(finalizationService, never()).succeed(any(), any(), any());
    }

    @Test
    void persistenceFailureAfterProviderResponseIsRecordedAmbiguous() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());
        RuntimeException dbFailure = new RuntimeException("db failure");
        when(finalizationService.succeedRag(
                eq(processing),
                any(RagCompletion.class),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(finalizationService).markAmbiguousAfterPersistenceFailure(
                eq(processing),
                eq(ChatTestFixtures.freeResponse()),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
    }

    @Test
    void unavailableLeaseWatchdogFailsBeforeProviderCall() {
        stubOwnedChat();
        when(reservationService.reserveOrReplay(
                eq(ChatTestFixtures.CHAT_ID),
                eq(request()),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenReturn(processing);
        var exception = new ru.safeai.gateway.chat.exception.ChatLeaseUnavailableException(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                "watchdog unavailable",
                null
        );
        when(leaseService.watch(any(), any(), any(), any()))
                .thenThrow(exception);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isSameAs(exception);

        verify(finalizationService).failBeforeProviderCall(
                eq(processing),
                eq("LEASE_WATCHDOG_UNAVAILABLE"),
                eq("CHAT_LEASE_WATCHDOG_UNAVAILABLE"),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
        verify(aiProvider, never()).sendMessage(any());
    }

    @Test
    void responseIsPersistedButNotReturnedAfterSecurityRevocation() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());
        when(finalizationService.succeedRag(
                eq(processing),
                any(RagCompletion.class),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenReturn(successResponse(false));
        doThrow(new ChatAccessRevokedException(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID
        )).when(securityStateService).assertStillActive(
                any(), any(), any(), any()
        );

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(ChatAccessRevokedException.class);

        verify(finalizationService).succeedRag(
                eq(processing),
                any(RagCompletion.class),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        );
    }

    @Test
    void createUsesClockAndTenantSafeUserLookup() {
        when(userRepository.findByIdAndOrganizationId(
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(ChatTestFixtures.user()));
        when(sessionRepository.saveAndFlush(any(ChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(
                new CreateChatRequest(" New chat "),
                ChatTestFixtures.principal()
        );

        assertThat(response.title()).isEqualTo("New chat");
        assertThat(response.createdAt()).isEqualTo(ChatTestFixtures.NOW);
        assertThat(response.updatedAt()).isEqualTo(ChatTestFixtures.NOW);
    }

    @Test
    void archiveHidesChatWithoutDeletingItsAuditHistory() {
        ChatSessionEntity session = ChatTestFixtures.session();
        when(sessionRepository
                .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.USER_ID,
                        ChatTestFixtures.ORGANIZATION_ID
                )).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(session)).thenReturn(session);

        service.archive(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.principal()
        );

        assertThat(session.getArchivedAt()).isEqualTo(ChatTestFixtures.NOW);
        assertThat(session.getArchivedByUserId())
                .isEqualTo(ChatTestFixtures.USER_ID);
        verify(sessionRepository).saveAndFlush(session);
        verify(auditEventService).record(
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class),
                eq(ChatTestFixtures.ORGANIZATION_ID),
                eq(AuditEventType.CHAT_ARCHIVED),
                argThat((Map<String, Object> details) ->
                        ChatTestFixtures.CHAT_ID.equals(details.get("chatId"))
                                && "AUDIT_PRESERVED".equals(
                                details.get("retentionMode")
                        )
                )
        );
    }

    @Test
    void userVisibleMessageQueryNeverReturnsSystemRowsByServiceContract() {
        when(sessionRepository.findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(ChatTestFixtures.session()));
        when(messageRepository.findVisibleBySessionId(
                ChatTestFixtures.CHAT_ID,
                PageRequest.of(0, 50)
        )).thenReturn(new SliceImpl<>(List.of()));

        assertThat(service.findMessages(
                ChatTestFixtures.CHAT_ID,
                PageRequest.of(0, 50),
                ChatTestFixtures.principal()
        )).isEmpty();

        verify(messageRepository).findVisibleBySessionId(
                ChatTestFixtures.CHAT_ID,
                PageRequest.of(0, 50)
        );
    }

    private void stubOwnedChat() {
        when(sessionRepository.existsByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID
        )).thenReturn(true);
        when(lockService.lock(ChatTestFixtures.CHAT_ID))
                .thenReturn(redisLock);
    }

    private void stubOwnedChatAndProcessing() {
        stubOwnedChat();
        when(reservationService.reserveOrReplay(
                eq(ChatTestFixtures.CHAT_ID),
                eq(request()),
                any(ru.safeai.gateway.common.security.SafeAiUserPrincipal.class)
        )).thenReturn(processing);
        when(leaseService.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        )).thenReturn(leaseWatch);
    }

    private static SendMessageRequest request() {
        return new SendMessageRequest(
                "Question",
                ChatTestFixtures.CLIENT_REQUEST_ID
        );
    }

    private static ChatProcessingContext processingContext() {
        AiChatRequest aiRequest = new AiChatRequest(
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                null,
                null,
                "Question",
                List.of()
        );
        return new ChatProcessingContext(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                ChatTestFixtures.NOW.plusSeconds(180),
                aiRequest,
                false
        );
    }

    private static ChatProcessingContext replayContext() {
        return new ChatProcessingContext(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                null,
                null,
                null,
                true
        );
    }

    private static SendMessageResponse successResponse(boolean replay) {
        ChatMessageEntity user = ChatMessageEntity.user(
                ChatTestFixtures.session(),
                "Question",
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.NOW.minusSeconds(10)
        );
        user.setId(ChatTestFixtures.USER_MESSAGE_ID);
        ChatMessageEntity assistant = ChatMessageEntity.completedAssistant(
                ChatTestFixtures.ASSISTANT_MESSAGE_ID,
                ChatTestFixtures.session(),
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.freeResponse(),
                ChatTestFixtures.NOW
        );
        ChatMapper mapper = new ChatMapper();
        return new SendMessageResponse(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                "SUCCEEDED",
                replay,
                mapper.toMessageResponse(user),
                mapper.toMessageResponse(assistant),
                ChatTestFixtures.NOW,
                ChatTestFixtures.NOW.minusSeconds(10),
                ChatTestFixtures.NOW
        );
    }

    private static AiProviderException providerException(boolean ambiguous) {
        return new AiProviderException(
                "openai",
                "requested-model",
                ambiguous ? null : 400,
                "provider-request-id",
                ambiguous
                        ? AiProviderErrorType.TIMEOUT
                        : AiProviderErrorType.INVALID_REQUEST,
                false,
                ambiguous,
                null,
                "provider failure",
                null
        );
    }
}
