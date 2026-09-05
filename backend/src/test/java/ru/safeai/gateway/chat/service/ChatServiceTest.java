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
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
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
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagService;
import ru.safeai.gateway.knowledge.rag.RagCompletion;
import ru.safeai.gateway.knowledge.rag.RagPreparation;
import ru.safeai.gateway.user.repository.UserRepository;

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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    /**
     * Keep the ordinary ChatService scenarios exactly on the current V48
     * accounting boundary. If the accounting contract changes, this fixture
     * changes with it instead of silently becoming under-reserved.
     */
    private static final long RESERVED_INPUT_UNITS =
            AiInputUnitEstimator.estimateBaseRequest(
                    "Question",
                    List.of()
            );

    private static final int MAX_OUTPUT_TOKENS =
            256;

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
        org.mockito.Mockito.lenient()
                .doAnswer(invocation -> {
                    ChatProcessingContext context = invocation.getArgument(
                            0,
                            ChatProcessingContext.class
                    );
                    return RagPreparation.general(
                            context.aiRequest()
                    );
                })
                .when(ragService)
                .prepare(
                        any(ChatProcessingContext.class),
                        any(SafeAiUserPrincipal.class)
                );

        org.mockito.Mockito.lenient()
                .doAnswer(invocation -> {
                    RagPreparation preparation = invocation.getArgument(
                            0,
                            RagPreparation.class
                    );
                    AiChatResponse response = invocation.getArgument(
                            1,
                            AiChatResponse.class
                    );
                    return RagCompletion.general(
                            preparation,
                            response
                    );
                })
                .when(ragService)
                .complete(
                        any(RagPreparation.class),
                        any(AiChatResponse.class)
                );
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
                any(SafeAiUserPrincipal.class)
        )).thenReturn(expected);

        SendMessageResponse result = service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        );

        assertThat(result).isSameAs(expected);

        InOrder order = inOrder(
                finalizationService,
                lockService,
                leaseService,
                aiProvider
        );
        order.verify(finalizationService).markProviderCallStarted(processing);
        order.verify(lockService).ensureValid(redisLock);
        order.verify(leaseService).ensureValid(leaseWatch);
        order.verify(aiProvider).sendMessage(processing.aiRequest());
        order.verify(lockService).ensureValid(redisLock);
        order.verify(leaseService).ensureValid(leaseWatch);

        verify(securityStateService).assertStillActive(
                eq(ChatTestFixtures.CHAT_ID),
                eq(ChatTestFixtures.TURN_ID),
                eq(ChatTestFixtures.CLIENT_REQUEST_ID),
                any(SafeAiUserPrincipal.class)
        );
        verify(leaseService).close(leaseWatch);
        verify(lockService).unlockQuietly(redisLock);
    }

    @Test
    void succeededReplaySkipsRateProviderLeaseAndQuotaPath() {
        stubOwnedChat();
        ChatProcessingContext replay = replayContext();
        when(reservationService.reserveOrReplay(
                eq(ChatTestFixtures.CHAT_ID),
                eq(request()),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(replay);
        when(finalizationService.replaySucceeded(
                eq(replay),
                any(SafeAiUserPrincipal.class)
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
                any(SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).markAmbiguous(
                any(), any(), any(), any(), any(), any(), any()
        );
        verify(leaseService).close(leaseWatch);
        verify(lockService).unlockQuietly(redisLock);
    }

    @Test
    void ragPreparationFailureBecomesStableFailedBeforeProviderIo() {
        stubOwnedChatAndProcessing();
        RuntimeException preparationFailure = new IllegalStateException(
                "rag preparation failed"
        );
        doThrow(preparationFailure)
                .when(ragService)
                .prepare(
                        eq(processing),
                        any(SafeAiUserPrincipal.class)
                );

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(ChatTurnFailedException.class)
                .extracting("code")
                .isEqualTo("KNOWLEDGE_RAG_PREPARATION_FAILED");

        verify(finalizationService).failBeforeProviderCall(
                eq(processing),
                eq("RAG_PREPARATION_FAILED"),
                eq("KNOWLEDGE_RAG_PREPARATION_FAILED"),
                any(SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).markProviderCallStarted(any());
        verify(aiProvider, never()).sendMessage(any());
        verify(leaseService).close(leaseWatch);
        verify(lockService).unlockQuietly(redisLock);
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
                any(SafeAiUserPrincipal.class)
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
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void nullProviderResponseIsClassifiedAmbiguousAndNeverFinalizedAsSuccess() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(null);

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
                eq("NULL_PROVIDER_RESPONSE"),
                eq("AI_PROVIDER_UNCLASSIFIED_OUTCOME"),
                any(SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).succeedRag(any(), any(), any());
    }

    @Test
    void ragCompletionFailureAfterProviderResponseIsMarkedAmbiguous() {
        stubOwnedChatAndProcessing();
        var providerResponse = ChatTestFixtures.freeResponse();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(providerResponse);

        RuntimeException completionFailure = new IllegalStateException(
                "rag completion failed"
        );
        doThrow(completionFailure)
                .when(ragService)
                .complete(
                        any(RagPreparation.class),
                        eq(providerResponse)
                );

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        ))
                .isInstanceOf(AiOutcomeAmbiguousException.class)
                .hasCause(completionFailure);

        verify(aiProvider).sendMessage(processing.aiRequest());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                eq(providerResponse.requestedModel()),
                eq(providerResponse.providerRequestId()),
                eq("IllegalStateException"),
                eq("RAG_COMPLETION_FAILED_AFTER_PROVIDER_RESPONSE"),
                any(SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).succeedRag(any(), any(), any());
    }

    @Test
    void lostRedisOwnershipAfterResponseCannotPersistAssistant() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());

        doNothing()
                .doThrow(new ChatLockUnavailableException("lost", null))
                .when(lockService)
                .ensureValid(redisLock);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(aiProvider).sendMessage(processing.aiRequest());
        verify(finalizationService, never()).succeedRag(any(), any(), any());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                eq("requested-model"),
                eq("provider-request-id"),
                eq("PROCESSING_OWNERSHIP_LOST"),
                eq("CHAT_PROCESSING_OWNERSHIP_LOST"),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void staleDbLeaseAfterResponseCannotPersistAssistant() {
        stubOwnedChatAndProcessing();
        when(aiProvider.sendMessage(processing.aiRequest()))
                .thenReturn(ChatTestFixtures.freeResponse());

        doNothing()
                .doThrow(new ChatStaleProcessorException(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.TURN_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID
                ))
                .when(leaseService)
                .ensureValid(leaseWatch);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(aiProvider).sendMessage(processing.aiRequest());
        verify(finalizationService, never()).succeedRag(any(), any(), any());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                eq("requested-model"),
                eq("provider-request-id"),
                eq("PROCESSING_OWNERSHIP_LOST"),
                eq("CHAT_PROCESSING_OWNERSHIP_LOST"),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void lostRedisOwnershipBeforeProviderCallSkipsProviderIo() {
        stubOwnedChatAndProcessing();
        doThrow(new ChatLockUnavailableException("lost", null))
                .when(lockService)
                .ensureValid(redisLock);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(aiProvider, never()).sendMessage(any());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq("PROCESSING_OWNERSHIP_LOST_BEFORE_PROVIDER_CALL"),
                eq("CHAT_PROCESSING_OWNERSHIP_LOST_BEFORE_PROVIDER_CALL"),
                any(SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).succeedRag(any(), any(), any());
    }

    @Test
    void staleDbLeaseBeforeProviderCallSkipsProviderIo() {
        stubOwnedChatAndProcessing();
        doThrow(new ChatStaleProcessorException(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID
        ))
                .when(leaseService)
                .ensureValid(leaseWatch);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(aiProvider, never()).sendMessage(any());
        verify(finalizationService).markAmbiguous(
                eq(processing),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq("PROCESSING_OWNERSHIP_LOST_BEFORE_PROVIDER_CALL"),
                eq("CHAT_PROCESSING_OWNERSHIP_LOST_BEFORE_PROVIDER_CALL"),
                any(SafeAiUserPrincipal.class)
        );
        verify(finalizationService, never()).succeedRag(any(), any(), any());
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
                any(SafeAiUserPrincipal.class)
        )).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(AiOutcomeAmbiguousException.class);

        verify(finalizationService).markAmbiguousAfterPersistenceFailure(
                eq(processing),
                eq(ChatTestFixtures.freeResponse()),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void unavailableLeaseWatchdogFailsBeforeProviderCall() {
        stubOwnedChat();
        when(reservationService.reserveOrReplay(
                eq(ChatTestFixtures.CHAT_ID),
                eq(request()),
                any(SafeAiUserPrincipal.class)
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
                any(SafeAiUserPrincipal.class)
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
                any(SafeAiUserPrincipal.class)
        )).thenReturn(successResponse(false));
        doThrow(new ChatAccessRevokedException(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID
        )).when(securityStateService).assertStillActive(any(), any(), any(), any());

        assertThatThrownBy(() -> service.sendMessage(
                ChatTestFixtures.CHAT_ID,
                request(),
                ChatTestFixtures.principal()
        )).isInstanceOf(ChatAccessRevokedException.class);

        verify(finalizationService).succeedRag(
                eq(processing),
                any(RagCompletion.class),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void preparedInputExceedingRouteReservationFailsDeterministicallyBeforeProviderIo() {
        ChatProcessingContext underReserved =
                processingContext(
                        RESERVED_INPUT_UNITS - 1L
                );

        stubOwnedChat();

        when(
                reservationService.reserveOrReplay(
                        eq(ChatTestFixtures.CHAT_ID),
                        eq(request()),
                        any(SafeAiUserPrincipal.class)
                )
        ).thenReturn(
                underReserved
        );

        when(
                leaseService.watch(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.TURN_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID,
                        ChatTestFixtures.PROCESSING_TOKEN
                )
        ).thenReturn(
                leaseWatch
        );

        assertThatThrownBy(() ->
                service.sendMessage(
                        ChatTestFixtures.CHAT_ID,
                        request(),
                        ChatTestFixtures.principal()
                )
        )
                .isInstanceOf(ChatTurnFailedException.class)
                .extracting("code")
                .isEqualTo(
                        "MODEL_ROUTE_INPUT_ENVELOPE_EXCEEDED"
                );

        verify(finalizationService).failBeforeProviderCall(
                eq(underReserved),
                eq("MODEL_ROUTE_ENVELOPE_EXCEEDED"),
                eq("MODEL_ROUTE_INPUT_ENVELOPE_EXCEEDED"),
                any(SafeAiUserPrincipal.class)
        );

        verify(finalizationService, never())
                .markProviderCallStarted(any());

        verify(aiProvider, never())
                .sendMessage(any());

        verify(leaseService)
                .close(leaseWatch);

        verify(lockService)
                .unlockQuietly(redisLock);
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
        when(sessionRepository.findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
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
        assertThat(session.getArchivedByUserId()).isEqualTo(ChatTestFixtures.USER_ID);
        verify(sessionRepository).saveAndFlush(session);
        verify(auditEventService).record(
                any(SafeAiUserPrincipal.class),
                eq(ChatTestFixtures.ORGANIZATION_ID),
                eq(AuditEventType.CHAT_ARCHIVED),
                argThat((Map<String, Object> details) ->
                        ChatTestFixtures.CHAT_ID.equals(details.get("chatId"))
                                && "AUDIT_PRESERVED".equals(details.get("retentionMode"))
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
        when(lockService.lock(ChatTestFixtures.CHAT_ID)).thenReturn(redisLock);
    }

    private void stubOwnedChatAndProcessing() {
        stubOwnedChat();
        when(reservationService.reserveOrReplay(
                eq(ChatTestFixtures.CHAT_ID),
                eq(request()),
                any(SafeAiUserPrincipal.class)
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
        return processingContext(
                RESERVED_INPUT_UNITS
        );
    }

    private static ChatProcessingContext processingContext(
            long reservedInputUnits
    ) {
        AiChatRequest aiRequest =
                new AiChatRequest(
                        ChatTestFixtures.USER_ID,
                        ChatTestFixtures.ORGANIZATION_ID,
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.PROVIDER_OPERATION_ID,
                        null,
                        null,
                        "Question",
                        List.of(),
                        reservedInputUnits,
                        MAX_OUTPUT_TOKENS
                );

        return ChatProcessingContextTestFixtures.processing(
                aiRequest
        );
    }

    private static ChatProcessingContext replayContext() {
        return ChatProcessingContextTestFixtures.replay();
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
