package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.ChatTurnAuditDetails;
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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

    public ChatTurnFinalizationService(
            ChatTurnRepository turnRepository,
            ChatMessageRepository messageRepository,
            ChatSessionRepository sessionRepository,
            ChatQuotaService quotaService,
            AuditEventService auditEventService,
            ChatMapper mapper,
            ChatMetrics metrics,
            Clock clock
    ) {
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
        this.mapper = Objects.requireNonNull(
                mapper,
                "mapper не должен быть null"
        );
        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @Transactional
    public void markProviderCallStarted(
            ChatProcessingContext context
    ) {
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );

        Instant now = clock.instant();

        int updated = turnRepository.markProviderCallStarted(
                context.turnId(),
                context.processingToken(),
                now
        );

        if (updated != 1) {
            throw stale(context);
        }
    }

    @Transactional
    public void failBeforeProviderCall(
            ChatProcessingContext context,
            String providerErrorType,
            String failureCode,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        Instant now = clock.instant();

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

        quotaService.releaseFailure(
                context.turnId(),
                now
        );

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                failureAudit(
                        context,
                        null,
                        null,
                        null,
                        providerErrorType,
                        failureCode,
                        false
                )
        );

        metrics.recordTerminalAfterCommit(
                ChatTurnState.FAILED
        );
    }

    @Transactional
    public SendMessageResponse succeed(
            ChatProcessingContext context,
            AiChatResponse response,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        Instant now = clock.instant();
        UUID assistantMessageId = UUID.randomUUID();

        ChatTurnEntity current = ownedTurn(
                context,
                currentUser
        );

        if (current.getState() != ChatTurnState.PROCESSING) {
            throw stale(context);
        }

        /*
         * Native @Modifying-запрос очищает persistence context.
         * Поэтому до его выполнения сохраняются только простые значения,
         * которые понадобятся после fenced update.
         */
        String provider = current.getProvider();
        Instant turnCreatedAt = current.getCreatedAt();

        /*
         * assistant_message_id использует отложенный внешний ключ.
         * Сначала выполняется fenced update turn, затем вставляется
         * assistant-сообщение в той же транзакции.
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

        /*
         * После clearAutomatically получаем новую managed-ссылку
         * на ChatSessionEntity, а не используем detached proxy
         * из загруженного ранее ChatTurnEntity.
         */
        ChatSessionEntity session =
                sessionRepository.getReferenceById(
                        context.chatId()
                );

        ChatMessageEntity assistant =
                ChatMessageEntity.completedAssistant(
                        assistantMessageId,
                        session,
                        context.userMessageId(),
                        response,
                        now
                );

        messageRepository.saveAndFlush(assistant);

        quotaService.settleSuccess(
                context.turnId(),
                response,
                now
        );

        touchChat(
                context,
                currentUser,
                now
        );

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_RECEIVED,
                successAudit(
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

        MessageResponse userMessage =
                mapper.toMessageResponse(
                        ownedMessage(
                                context.userMessageId(),
                                context.chatId(),
                                currentUser.getOrganizationId()
                        )
                );

        MessageResponse assistantMessage =
                mapper.toMessageResponse(assistant);

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
                now
        );
    }

    @Transactional
    public void failUnambiguous(
            ChatProcessingContext context,
            AiProviderException exception,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );
        Objects.requireNonNull(
                exception,
                "exception не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        Instant now = clock.instant();

        String failureCode =
                "AI_PROVIDER_"
                        + exception.getErrorType().name();

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

        quotaService.releaseFailure(
                context.turnId(),
                now
        );

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                failureAudit(
                        context,
                        exception.getProvider(),
                        exception.getModel(),
                        exception.getProviderRequestId(),
                        exception.getErrorType().name(),
                        failureCode,
                        false
                )
        );

        metrics.recordTerminalAfterCommit(
                ChatTurnState.FAILED
        );
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
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        Instant now = clock.instant();

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

        quotaService.markAmbiguous(
                context.turnId(),
                now
        );

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                failureAudit(
                        context,
                        provider,
                        requestedModel,
                        providerRequestId,
                        providerErrorType,
                        failureCode,
                        true
                )
        );

        metrics.recordTerminalAfterCommit(
                ChatTurnState.AMBIGUOUS
        );
    }

    /**
     * Вызывается после отката транзакции сохранения успешного
     * ответа AI-провайдера.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAmbiguousAfterPersistenceFailure(
            ChatProcessingContext context,
            AiChatResponse response,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

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
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        ChatTurnEntity turn = ownedTurn(
                context,
                currentUser
        );

        if (turn.getState() != ChatTurnState.SUCCEEDED
                || turn.getAssistantMessageId() == null
                || turn.getCompletedAt() == null) {
            throw new IllegalStateException(
                    "Replay вызван для неуспешного turn: "
                            + turn.getId()
            );
        }

        MessageResponse user =
                mapper.toMessageResponse(
                        ownedMessage(
                                turn.getUserMessageId(),
                                context.chatId(),
                                currentUser.getOrganizationId()
                        )
                );

        MessageResponse assistant =
                mapper.toMessageResponse(
                        ownedMessage(
                                turn.getAssistantMessageId(),
                                context.chatId(),
                                currentUser.getOrganizationId()
                        )
                );

        return new SendMessageResponse(
                context.chatId(),
                turn.getId(),
                turn.getClientRequestId(),
                turn.getProviderOperationId(),
                turn.getState().name(),
                true,
                user,
                assistant,
                turn.getUpdatedAt(),
                turn.getCreatedAt(),
                turn.getCompletedAt()
        );
    }

    @Transactional(readOnly = true)
    public ChatTurnStatusResponse status(
            UUID chatId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );
        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        ChatTurnEntity turn =
                turnRepository
                        .findBySession_IdAndClientRequestIdAndUser_IdAndOrganization_Id(
                                chatId,
                                clientRequestId,
                                currentUser.getId(),
                                currentUser.getOrganizationId()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Chat turn не найден"
                                )
                        );

        MessageResponse user =
                mapper.toMessageResponse(
                        ownedMessage(
                                turn.getUserMessageId(),
                                chatId,
                                currentUser.getOrganizationId()
                        )
                );

        MessageResponse assistant =
                turn.getAssistantMessageId() == null
                        ? null
                        : mapper.toMessageResponse(
                                ownedMessage(
                                        turn.getAssistantMessageId(),
                                        chatId,
                                        currentUser.getOrganizationId()
                                )
                        );

        return mapper.toTurnStatusResponse(
                turn,
                user,
                assistant
        );
    }

    private ChatTurnEntity ownedTurn(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        return turnRepository
                .findByIdAndSession_IdAndUser_IdAndOrganization_Id(
                        context.turnId(),
                        context.chatId(),
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Chat turn не найден: "
                                        + context.turnId()
                        )
                );
    }

    private ChatMessageEntity ownedMessage(
            UUID messageId,
            UUID chatId,
            UUID organizationId
    ) {
        return messageRepository
                .findOwnedMessage(
                        messageId,
                        chatId,
                        organizationId
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Сообщение не найдено: "
                                        + messageId
                        )
                );
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
            throw new ResourceNotFoundException(
                    "Чат не найден: "
                            + context.chatId()
            );
        }
    }

    private ChatStaleProcessorException stale(
            ChatProcessingContext context
    ) {
        return new ChatStaleProcessorException(
                context.chatId(),
                context.turnId(),
                context.clientRequestId()
        );
    }

    private ChatTurnAuditDetails successAudit(
            ChatProcessingContext context,
            UUID assistantMessageId,
            AiChatResponse response,
            String provider
    ) {
        return new ChatTurnAuditDetails(
                context.chatId(),
                context.turnId(),
                context.userMessageId(),
                assistantMessageId,
                context.clientRequestId(),
                context.providerOperationId(),
                ChatTurnState.SUCCEEDED.name(),
                provider,
                response.requestedModel(),
                response.model(),
                response.providerRequestId(),
                null,
                null,
                false,
                response.inputTokens(),
                response.outputTokens(),
                response.costUsd(),
                response.usageStatus().name(),
                response.pricingStatus().name(),
                response.currency()
        );
    }

    private ChatTurnAuditDetails failureAudit(
            ChatProcessingContext context,
            String provider,
            String requestedModel,
            String providerRequestId,
            String providerErrorType,
            String failureCode,
            boolean ambiguous
    ) {
        return new ChatTurnAuditDetails(
                context.chatId(),
                context.turnId(),
                context.userMessageId(),
                null,
                context.clientRequestId(),
                context.providerOperationId(),
                ambiguous
                        ? ChatTurnState.AMBIGUOUS.name()
                        : ChatTurnState.FAILED.name(),
                provider,
                requestedModel,
                null,
                providerRequestId,
                providerErrorType,
                failureCode,
                ambiguous,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}