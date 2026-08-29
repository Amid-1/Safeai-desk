package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.chat.exception.ChatTurnInProgressException;
import ru.safeai.gateway.chat.exception.IdempotencyKeyReusedException;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatHistoryRepository;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.repository.ChatTurnMutexRepository;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelRouteResult;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.service.ModelRoutingService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatTurnReservationService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatTurnRepository turnRepository;
    private final ChatTurnMutexRepository mutexRepository;
    private final ChatHistoryRepository historyRepository;
    private final AiHistoryBuilder historyBuilder;
    private final ChatContentNormalizer contentNormalizer;
    private final RedisRateLimitService rateLimitService;
    private final ChatQuotaService quotaService;
    private final ChatTurnRecoveryCoordinator recoveryCoordinator;
    private final AuditEventService auditEventService;
    private final ModelRoutingService modelRoutingService;
    private final ChatProperties chatProperties;
    private final ChatMetrics metrics;
    private final Clock clock;

    public ChatTurnReservationService(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            ChatTurnRepository turnRepository,
            ChatTurnMutexRepository mutexRepository,
            ChatHistoryRepository historyRepository,
            AiHistoryBuilder historyBuilder,
            ChatContentNormalizer contentNormalizer,
            RedisRateLimitService rateLimitService,
            ChatQuotaService quotaService,
            ChatTurnRecoveryCoordinator recoveryCoordinator,
            AuditEventService auditEventService,
            ModelRoutingService modelRoutingService,
            ChatProperties chatProperties,
            ChatMetrics metrics,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.turnRepository = turnRepository;
        this.mutexRepository = mutexRepository;
        this.historyRepository = historyRepository;
        this.historyBuilder = historyBuilder;
        this.contentNormalizer = contentNormalizer;
        this.rateLimitService = rateLimitService;
        this.quotaService = quotaService;
        this.recoveryCoordinator = recoveryCoordinator;
        this.auditEventService = auditEventService;
        this.modelRoutingService = modelRoutingService;
        this.chatProperties = chatProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * One short transaction: idempotency lookup, complete-turn history
     * validation, USER/PROCESSING persistence, durable quota/audit intent and
     * finally the external rate-limit reservation. Provider I/O starts only
     * after this method commits.

     * ChatTurnFailedException and AiOutcomeAmbiguousException may be thrown
     * after a stale PROCESSING turn has been moved to a terminal state.
     * Those expected state transitions must be committed, not rolled back.
     */
    @Transactional(
            noRollbackFor = {
                    ChatTurnFailedException.class,
                    AiOutcomeAmbiguousException.class,
                    ModelRouteDeniedException.class
            }
    )
    public ChatProcessingContext reserveOrReplay(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        requireArguments(
                chatId,
                request,
                currentUser
        );

        String content =
                contentNormalizer.normalize(
                        request.content()
                );

        String contentHash = contentNormalizer.requestHash(
                content,
                request.knowledgeBaseId(),
                request.knowledgeMode()
        );

        UUID clientRequestId =
                request.clientRequestId();

        Instant now =
                clock.instant();

        // Serializes all reservations for one chat across application nodes.
        mutexRepository.lockChat(chatId);

        ChatSessionEntity session =
                findOwnedSession(
                        chatId,
                        currentUser
                );

        var existing =
                turnRepository.findByIdempotencyKeyForUpdate(
                        chatId,
                        clientRequestId
                );

        if (existing.isPresent()) {
            return resolveExisting(
                    existing.get(),
                    contentHash,
                    now
            );
        }

        var active =
                turnRepository.findSessionTurnByStateForUpdate(
                        chatId,
                        ChatTurnState.PROCESSING
                );

        if (active.isPresent()) {
            ChatTurnEntity processing =
                    active.get();

            if (processing.getLeaseUntil() != null
                    && !processing.getLeaseUntil().isAfter(now)) {
                recoverExpired(
                        processing,
                        now
                );
            } else {
                throw new ChatBusyException(
                        "В этом чате уже обрабатывается другой запрос"
                );
            }
        }

        /*
         * Validate and materialize the complete-turn history before any
         * external side effect. Malformed legacy data therefore cannot consume
         * a rate-limit slot or create a partial reservation.
         */
        List<AiMessage> history =
                historyBuilder.build(
                        historyRepository.findNewestSucceededTurns(
                                chatId,
                                currentUser.getOrganizationId(),
                                currentUser.getId(),
                                chatProperties.historyTurnLimit()
                        )
                );

        UUID turnId =
                UUID.randomUUID();

        /*
         * V45 Model Control Plane decision is persisted after ChatTurn
         * idempotency lookup but before USER/PROCESSING/quota/rate-limit
         * reservation. The selected route must exactly match the physical
         * single runtime adapter; true provider/model multiplexing remains
         * a later data-plane milestone.
         */
        ModelRouteResult route =
                modelRoutingService.decide(
                        new ModelRouteRequest(
                                currentUser.getOrganizationId(),
                                currentUser.getId(),
                                chatId,
                                turnId,
                                clientRequestId,
                                contentHash,
                                null,
                                content,
                                history,
                                Set.of()
                        ),
                        currentUser,
                        now
                );

        UUID userMessageId =
                UUID.randomUUID();

        UUID providerOperationId =
                UUID.randomUUID();

        UUID processingToken =
                UUID.randomUUID();

        Instant leaseUntil =
                now.plus(
                        chatProperties.processingLease()
                );

        /*
         * Persist the idempotency identity before external rate-limit I/O.
         * The transaction-scoped advisory lock plus unique constraints make
         * this the durable source of truth. All database validation, quota and
         * audit-outbox work is completed before consuming the Redis rate slot.
         */
        ChatMessageEntity userMessage =
                ChatMessageEntity.user(
                        session,
                        content,
                        clientRequestId,
                        now
                );

        userMessage.setId(userMessageId);

        messageRepository.saveAndFlush(
                userMessage
        );

        ChatTurnEntity turn =
                ChatTurnEntity.processing(
                        turnId,
                        session,
                        clientRequestId,
                        contentHash,
                        providerOperationId,
                        userMessageId,
                        processingToken,
                        now,
                        leaseUntil,
                        route.provider(),
                        route.providerModelId(),
                        route.decisionId(),
                        request.knowledgeBaseId(),
                        request.knowledgeMode()
                );

        turnRepository.saveAndFlush(
                turn
        );

        quotaService.reserve(
                chatId,
                turnId,
                clientRequestId,
                currentUser.getOrganizationId(),
                currentUser.getId()
        );

        session.touch(now);

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.CHAT_MESSAGE_SENT,
                Map.of(
                        "chatId", chatId,
                        "turnId", turnId,
                        "messageId", userMessageId,
                        "clientRequestId", clientRequestId,
                        "providerOperationId", providerOperationId,
                        "modelRouteDecisionId", route.decisionId(),
                        "modelKey", route.modelKey(),
                        "provider", route.provider(),
                        "requestedModel", route.providerModelId(),
                        "messageLength", content.length()
                )
        );

        /*
         * Redis rate limiting is the only non-transactional reservation in
         * this method, so it runs last. Rejection rolls back USER/turn/quota/
         * audit rows; earlier database failures do not consume a rate slot.
         */
        rateLimitService.checkAiMessageAllowed(
                currentUser
        );

        metrics.recordReservedAfterCommit();

        AiChatRequest aiRequest =
                new AiChatRequest(
                        currentUser.getId(),
                        currentUser.getOrganizationId(),
                        chatId,
                        providerOperationId,
                        null,
                        null,
                        content,
                        history
                );

        return new ChatProcessingContext(
                chatId,
                turnId,
                userMessageId,
                clientRequestId,
                providerOperationId,
                processingToken,
                leaseUntil,
                route.decisionId(),
                aiRequest,
                request.knowledgeBaseId(),
                request.knowledgeMode(),
                false
        );
    }

    private ChatProcessingContext resolveExisting(
            ChatTurnEntity turn,
            String contentHash,
            Instant now
    ) {
        if (!turn.getRequestContentHash().equals(contentHash)) {
            throw new IdempotencyKeyReusedException(
                    turn.getSession().getId(),
                    turn.getId(),
                    turn.getClientRequestId()
            );
        }

        metrics.recordReplay(
                turn.getState()
        );

        return switch (turn.getState()) {
            case SUCCEEDED ->
                    new ChatProcessingContext(
                            turn.getSession().getId(),
                            turn.getId(),
                            turn.getUserMessageId(),
                            turn.getClientRequestId(),
                            turn.getProviderOperationId(),
                            null,
                            null,
                            turn.getModelRouteDecisionId(),
                            null,
                            turn.getKnowledgeBaseId(),
                            turn.getKnowledgeMode(),
                            true
                    );

            case FAILED ->
                    throw new ChatTurnFailedException(
                            turn.getSession().getId(),
                            turn.getId(),
                            turn.getClientRequestId(),
                            turn.getFailureCode(),
                            null
                    );

            case AMBIGUOUS ->
                    throw new AiOutcomeAmbiguousException(
                            turn.getSession().getId(),
                            turn.getId(),
                            turn.getClientRequestId(),
                            null
                    );

            case PROCESSING -> {
                if (turn.getLeaseUntil() != null
                        && !turn.getLeaseUntil().isAfter(now)) {
                    recoverExpired(
                            turn,
                            now
                    );

                    if (turn.getProviderCallStartedAt() == null) {
                        throw new ChatTurnFailedException(
                                turn.getSession().getId(),
                                turn.getId(),
                                turn.getClientRequestId(),
                                "STALE_BEFORE_PROVIDER_CALL",
                                null
                        );
                    }

                    throw new AiOutcomeAmbiguousException(
                            turn.getSession().getId(),
                            turn.getId(),
                            turn.getClientRequestId(),
                            null
                    );
                }

                Duration retryAfter =
                        Duration.between(
                                now,
                                turn.getLeaseUntil()
                        );

                yield throwInProgress(
                        turn,
                        retryAfter
                );
            }

            case NEW ->
                    throwInProgress(
                            turn,
                            Duration.ofSeconds(1)
                    );
        };
    }

    private void recoverExpired(
            ChatTurnEntity turn,
            Instant now
    ) {
        recoveryCoordinator.recoverInline(
                turn,
                now
        );
    }

    private ChatProcessingContext throwInProgress(
            ChatTurnEntity turn,
            Duration retryAfter
    ) {
        throw new ChatTurnInProgressException(
                turn.getSession().getId(),
                turn.getId(),
                turn.getClientRequestId(),
                retryAfter.isNegative()
                        ? Duration.ZERO
                        : retryAfter
        );
    }

    private ChatSessionEntity findOwnedSession(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        return sessionRepository
                .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                        chatId,
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Чат не найден: " + chatId
                        )
                );
    }

    private static void requireArguments(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                request.clientRequestId(),
                "clientRequestId не должен быть null"
        );

        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
    }
}
