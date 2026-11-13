package ru.safeai.gateway.chat.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatTurnStatusResponse;
import ru.safeai.gateway.chat.dto.MessageResponse;
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
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;
import ru.safeai.gateway.knowledge.rag.AnswerPassportService;
import ru.safeai.gateway.knowledge.rag.RagCompletion;
import ru.safeai.gateway.knowledge.rag.RagPreparation;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagExecutionPermitService;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagContextRevokedException;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns fenced terminal transitions for a durable chat turn.
 *
 * <p>The service keeps Spring transaction boundaries explicit. Read/replay DTO
 * reconstruction and audit-payload construction are delegated to package-local
 * collaborators so the transactional state machine remains easy to review.</p>
 */
@Service
public class ChatTurnFinalizationService {

    private final ChatTurnRepository turnRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatQuotaService quotaService;
    private final AuditEventService auditEventService;
    private final ChatMapper mapper;
    private final ChatMetrics metrics;
    private final Clock clock;
    private final ChatDatabaseClockRepository databaseClock;
    private final AnswerPassportService answerPassportService;
    private final ChatTurnReadSupport readSupport;
    private final KnowledgeRagExecutionPermitService knowledgeExecutionPermits;

    /** Constructor signature intentionally remains unchanged for Spring/tests. */
    public ChatTurnFinalizationService(
            ChatTurnRepository turnRepository,
            ChatMessageRepository messageRepository,
            ChatSessionRepository sessionRepository,
            ChatQuotaService quotaService,
            AuditEventService auditEventService,
            ChatMapper mapper,
            ChatMetrics metrics,
            Clock clock,
            AnswerPassportService answerPassportService
    ) {
        this(turnRepository, messageRepository, sessionRepository, quotaService,
                auditEventService, mapper, metrics, clock, answerPassportService, null, null);
    }

    public ChatTurnFinalizationService(
            ChatTurnRepository turnRepository,
            ChatMessageRepository messageRepository,
            ChatSessionRepository sessionRepository,
            ChatQuotaService quotaService,
            AuditEventService auditEventService,
            ChatMapper mapper,
            ChatMetrics metrics,
            Clock clock,
            AnswerPassportService answerPassportService,
            ChatDatabaseClockRepository databaseClock
    ) {
        this(turnRepository, messageRepository, sessionRepository, quotaService,
                auditEventService, mapper, metrics, clock, answerPassportService,
                databaseClock, null);
    }

    @Autowired
    public ChatTurnFinalizationService(
            ChatTurnRepository turnRepository,
            ChatMessageRepository messageRepository,
            ChatSessionRepository sessionRepository,
            ChatQuotaService quotaService,
            AuditEventService auditEventService,
            ChatMapper mapper,
            ChatMetrics metrics,
            Clock clock,
            AnswerPassportService answerPassportService,
            ChatDatabaseClockRepository databaseClock,
            KnowledgeRagExecutionPermitService knowledgeExecutionPermits
    ) {
        this.databaseClock = databaseClock;
        this.knowledgeExecutionPermits = knowledgeExecutionPermits;
        this.turnRepository = Objects.requireNonNull(
                turnRepository,
                "turnRepository не должен быть null"
        );
        this.messageRepository = Objects.requireNonNull(
                messageRepository,
                "messageRepository не должен быть null"
        );
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository,
                "sessionRepository не должен быть null"
        );
        this.quotaService = Objects.requireNonNull(
                quotaService,
                "quotaService не должен быть null"
        );
        this.auditEventService = Objects.requireNonNull(
                auditEventService,
                "auditEventService не должен быть null"
        );
        this.mapper = Objects.requireNonNull(mapper, "mapper не должен быть null");
        this.metrics = Objects.requireNonNull(metrics, "metrics не должен быть null");
        this.clock = Objects.requireNonNull(clock, "clock не должен быть null");
        this.answerPassportService = Objects.requireNonNull(
                answerPassportService,
                "answerPassportService не должен быть null"
        );
        this.readSupport = new ChatTurnReadSupport(
                turnRepository,
                messageRepository,
                mapper,
                answerPassportService
        );
    }

    @Transactional
    public void failBeforeProviderCall(
            ChatProcessingContext context,
            String providerErrorType,
            String failureCode,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        Instant now = (databaseClock == null ? clock.instant() : databaseClock.now());
        int updated = turnRepository.markFailedBeforeProviderCall(
                context.turnId(),
                context.processingToken(),
                providerErrorType,
                failureCode,
                now
        );

        if (updated != 1) {
            throw stale(context);
        }

        quotaService.releaseFailure(context.turnId(), now);
        auditEventService.recordPrincipalSnapshot(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                ChatTurnAuditDetailsFactory.failure(
                        context,
                        null,
                        null,
                        null,
                        providerErrorType,
                        failureCode,
                        false
                )
        );
        metrics.recordTerminalAfterCommit(ChatTurnState.FAILED);
    }

    @Transactional
    public SendMessageResponse succeed(
            ChatProcessingContext context,
            AiChatResponse response,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        return succeedRag(
                context,
                RagCompletion.general(
                        RagPreparation.general(context.aiRequest()),
                        response
                ),
                currentUser
        );
    }

    @Transactional
    public SendMessageResponse succeedRag(
            ChatProcessingContext context,
            RagCompletion completion,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        AiChatResponse response = Objects.requireNonNull(
                Objects.requireNonNull(
                        completion,
                        "completion не должен быть null"
                ).response(),
                "response не должен быть null"
        );

        RagPreparation ragPreparation = completion.preparation();
        if (ragPreparation != null && ragPreparation.mode().usesKnowledge()) {
            if (knowledgeExecutionPermits == null || ragPreparation.executionPermitId() == null) {
                throw new KnowledgeRagContextRevokedException();
            }
            // Joins this transaction. The base/document FOR SHARE locks are held
            // through message, Answer Passport, quota and terminal ChatTurn commit.
            knowledgeExecutionPermits.assertFinalizationAllowed(
                    ragPreparation.executionPermitId(), context.turnId(),
                    currentUser.getOrganizationId());
        }

        Instant now = (databaseClock == null ? clock.instant() : databaseClock.now());
        UUID assistantMessageId = UUID.randomUUID();

        ChatTurnEntity current = readSupport.ownedTurn(context, currentUser);
        if (current.getState() != ChatTurnState.PROCESSING) {
            throw stale(context);
        }

        /*
         * markSucceeded is a native modifying query that clears the persistence
         * context. Preserve only scalar values needed after the fenced update.
         */
        String provider = current.getProvider();
        Instant turnCreatedAt = current.getCreatedAt();

        /*
         * assistant_message_id uses a deferred FK. The turn is fenced first,
         * then the assistant row is inserted in the same transaction.
         */
        int updated = turnRepository.markSucceeded(
                context.turnId(),
                context.processingToken(),
                assistantMessageId,
                response.requestedModel(),
                response.model(),
                response.providerRequestId(),
                now
        );

        if (updated != 1) {
            throw stale(context);
        }

        ChatSessionEntity session = sessionRepository.getReferenceById(
                context.chatId()
        );
        ChatMessageEntity assistant = ChatMessageEntity.completedAssistant(
                assistantMessageId,
                session,
                context.userMessageId(),
                response,
                now
        );
        messageRepository.saveAndFlush(assistant);

        AnswerPassportResponse answerPassport = answerPassportService.persist(
                context,
                assistantMessageId,
                provider,
                completion,
                currentUser,
                now
        );

        quotaService.settleSuccess(context.turnId(), response, now);
        touchChat(context, currentUser, now);

        auditEventService.recordPrincipalSnapshot(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_RECEIVED,
                ChatTurnAuditDetailsFactory.success(
                        context,
                        assistantMessageId,
                        response,
                        provider
                )
        );

        metrics.recordTerminalAfterCommit(
                ChatTurnState.SUCCEEDED,
                turnCreatedAt,
                now
        );

        MessageResponse userMessage = mapper.toMessageResponse(
                readSupport.ownedMessage(
                        context.userMessageId(),
                        context.chatId(),
                        currentUser.getOrganizationId()
                )
        );
        MessageResponse assistantMessage = mapper.toMessageResponse(assistant);

        return new SendMessageResponse(
                context.chatId(),
                context.turnId(),
                context.clientRequestId(),
                context.providerOperationId(),
                ChatTurnState.SUCCEEDED.name(),
                false,
                userMessage,
                assistantMessage,
                now,
                turnCreatedAt,
                now,
                answerPassport
        );
    }

    /**
     * The provider returned successfully, but Knowledge was revoked before the
     * atomic ChatTurn finalization lock was acquired. Do not persist an answer;
     * still settle the known provider usage so accounting does not erase a real cost.
     */
    @Transactional
    public void failAfterProviderContextRevoked(
            ChatProcessingContext context,
            RagCompletion completion,
            AiChatResponse response,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(currentUser, "currentUser");
        RagPreparation preparation = completion == null ? null : completion.preparation();
        if (preparation == null || preparation.executionPermitId() == null
                || knowledgeExecutionPermits == null) {
            throw new KnowledgeRagContextRevokedException();
        }

        Instant now = (databaseClock == null ? clock.instant() : databaseClock.now());
        ChatTurnEntity current = readSupport.ownedTurn(context, currentUser);
        int updated = turnRepository.markFailed(
                context.turnId(), context.processingToken(), current.getProvider(),
                response.requestedModel(), response.providerRequestId(),
                "KNOWLEDGE_CONTEXT_REVOKED_AFTER_PROVIDER_RESPONSE",
                "KNOWLEDGE_CONTEXT_REVOKED_BEFORE_FINALIZATION", now);
        if (updated != 1) throw stale(context);

        knowledgeExecutionPermits.revokePermitAfterRejectedFinalization(preparation.executionPermitId());
        quotaService.settleSuccess(context.turnId(), response, now);
        auditEventService.recordPrincipalSnapshot(
                currentUser, currentUser.getOrganizationId(), AuditEventType.AI_RESPONSE_FAILED,
                ChatTurnAuditDetailsFactory.failure(
                        context, current.getProvider(), response.requestedModel(),
                        response.providerRequestId(),
                        "KNOWLEDGE_CONTEXT_REVOKED_AFTER_PROVIDER_RESPONSE",
                        "KNOWLEDGE_CONTEXT_REVOKED_BEFORE_FINALIZATION", false));
        metrics.recordTerminalAfterCommit(ChatTurnState.FAILED);
    }

    /** No physical call was admitted because a retry was denied after erasure began. */
    @Transactional
    public void failBeforePhysicalAttemptAfterKnowledgeRevocation(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        Instant now = (databaseClock == null ? clock.instant() : databaseClock.now());
        int updated = turnRepository.markFailed(
                context.turnId(), context.processingToken(), null, null, null,
                "KNOWLEDGE_CONTEXT_REVOKED_BEFORE_PHYSICAL_ATTEMPT",
                "KNOWLEDGE_CONTEXT_REVOKED_DURING_RETRY", now);
        if (updated != 1) throw stale(context);
        quotaService.releaseFailure(context.turnId(), now);
        auditEventService.recordPrincipalSnapshot(
                currentUser, currentUser.getOrganizationId(), AuditEventType.AI_RESPONSE_FAILED,
                ChatTurnAuditDetailsFactory.failure(
                        context, null, null, null,
                        "KNOWLEDGE_CONTEXT_REVOKED_BEFORE_PHYSICAL_ATTEMPT",
                        "KNOWLEDGE_CONTEXT_REVOKED_DURING_RETRY", false));
        metrics.recordTerminalAfterCommit(ChatTurnState.FAILED);
    }

    @Transactional
    public void failUnambiguous(
            ChatProcessingContext context,
            AiProviderException exception,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(exception, "exception не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        Instant now = (databaseClock == null ? clock.instant() : databaseClock.now());
        String failureCode = "AI_PROVIDER_" + exception.getErrorType().name();

        int updated = turnRepository.markFailed(
                context.turnId(),
                context.processingToken(),
                exception.getProvider(),
                exception.getModel(),
                exception.getProviderRequestId(),
                exception.getErrorType().name(),
                failureCode,
                now
        );

        if (updated != 1) {
            throw stale(context);
        }

        quotaService.releaseFailure(context.turnId(), now);
        auditEventService.recordPrincipalSnapshot(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                ChatTurnAuditDetailsFactory.failure(
                        context,
                        exception.getProvider(),
                        exception.getModel(),
                        exception.getProviderRequestId(),
                        exception.getErrorType().name(),
                        failureCode,
                        false
                )
        );
        metrics.recordTerminalAfterCommit(ChatTurnState.FAILED);
    }

    @Transactional
    public void markAmbiguous(
            ChatProcessingContext context,
            String provider,
            String requestedModel,
            String providerRequestId,
            String providerErrorType,
            String failureCode,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        Instant now = (databaseClock == null ? clock.instant() : databaseClock.now());
        int updated = turnRepository.markAmbiguous(
                context.turnId(),
                context.processingToken(),
                provider,
                requestedModel,
                providerRequestId,
                providerErrorType,
                failureCode,
                now
        );

        if (updated != 1) {
            throw stale(context);
        }

        quotaService.markAmbiguous(context.turnId(), now);
        auditEventService.recordPrincipalSnapshot(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                ChatTurnAuditDetailsFactory.failure(
                        context,
                        provider,
                        requestedModel,
                        providerRequestId,
                        providerErrorType,
                        failureCode,
                        true
                )
        );
        metrics.recordTerminalAfterCommit(ChatTurnState.AMBIGUOUS);
    }

    /**
     * Persists AMBIGUOUS after the transaction that attempted to persist the
     * successful provider response has rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAmbiguousAfterPersistenceFailure(
            ChatProcessingContext context,
            AiChatResponse response,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(response, "response не должен быть null");

        markAmbiguous(
                context,
                null,
                response.requestedModel(),
                response.providerRequestId(),
                "PERSISTENCE_FAILURE_AFTER_PROVIDER_RESPONSE",
                "AI_RESPONSE_PERSISTENCE_FAILED",
                currentUser
        );
    }

    @Transactional(readOnly = true)
    public SendMessageResponse replaySucceeded(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        return readSupport.replaySucceeded(context, currentUser);
    }

    @Transactional(readOnly = true)
    public ChatTurnStatusResponse status(
            UUID chatId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        return readSupport.status(chatId, clientRequestId, currentUser);
    }

    private void touchChat(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser,
            Instant now
    ) {
        int updated = sessionRepository.touchOwned(
                context.chatId(),
                currentUser.getId(),
                currentUser.getOrganizationId(),
                now
        );

        if (updated != 1) {
            throw new ResourceNotFoundException("Чат не найден: " + context.chatId());
        }
    }

    private static ChatStaleProcessorException stale(
            ChatProcessingContext context
    ) {
        return new ChatStaleProcessorException(
                context.chatId(),
                context.turnId(),
                context.clientRequestId()
        );
    }
}
