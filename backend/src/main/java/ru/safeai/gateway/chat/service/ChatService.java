package ru.safeai.gateway.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.ChatTurnStatusResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatLeaseUnavailableException;
import ru.safeai.gateway.chat.exception.ChatStaleProcessorException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AiProvider aiProvider;
    private final AuditEventService auditEventService;
    private final ChatTurnReservationService reservationService;
    private final ChatTurnFinalizationService finalizationService;
    private final ChatSecurityStateService securityStateService;
    private final ChatMapper mapper;
    private final ChatLockService lockService;
    private final ChatTurnLeaseService leaseService;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatService(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            UserRepository userRepository,
            AiProvider aiProvider,
            AuditEventService auditEventService,
            ChatTurnReservationService reservationService,
            ChatTurnFinalizationService finalizationService,
            ChatSecurityStateService securityStateService,
            ChatMapper mapper,
            ChatLockService lockService,
            ChatTurnLeaseService leaseService,
            ChatProperties properties,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.aiProvider = aiProvider;
        this.auditEventService = auditEventService;
        this.reservationService = reservationService;
        this.finalizationService = finalizationService;
        this.securityStateService = securityStateService;
        this.mapper = mapper;
        this.lockService = lockService;
        this.leaseService = leaseService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ChatResponse create(
            CreateChatRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        requireCurrentUser(currentUser);

        UserEntity user = userRepository
                .findByIdAndOrganizationId(
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + currentUser.getId()
                ));

        Instant now = clock.instant();
        ChatSessionEntity session = ChatSessionEntity.create(
                user,
                request.title(),
                now
        );
        ChatSessionEntity saved = sessionRepository.saveAndFlush(session);

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.CHAT_CREATED,
                Map.of(
                        "chatId", saved.getId(),
                        "titleLength", saved.getTitle().length(),
                        "defaultTitle", request.title() == null
                                || request.title().isBlank()
                )
        );
        return mapper.toChatResponse(saved);
    }

    @Transactional(readOnly = true)
    public Slice<ChatResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        requireCurrentUser(currentUser);
        Objects.requireNonNull(pageable, "pageable не должен быть null");
        return sessionRepository.findByUser_IdAndOrganization_Id(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                pageable
        ).map(mapper::toChatResponse);
    }

    @Transactional(readOnly = true)
    public ChatDetailsResponse findById(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);
        List<MessageResponse> messages = messageRepository
                .findVisibleBySessionId(
                        chatId,
                        org.springframework.data.domain.PageRequest.of(
                                0,
                                properties.detailsMessageLimit(),
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Order.desc(
                                                "createdAt"
                                        ),
                                        org.springframework.data.domain.Sort.Order.desc(
                                                "id"
                                        )
                                )
                        )
                )
                .getContent()
                .reversed()
                .stream()
                .map(mapper::toMessageResponse)
                .toList();
        return mapper.toChatDetailsResponse(session, messages);
    }

    public SendMessageResponse sendMessage(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(
                request.clientRequestId(),
                "clientRequestId не должен быть null"
        );

        // Foreign/nonexistent IDs never allocate Redis keys.
        assertOwnedChatExists(chatId, currentUser);

        ChatLockService.ChatLock redisLock =
                lockService.lock(chatId);

        ChatTurnLeaseService.LeaseWatch leaseWatch = null;

        try {
            ChatProcessingContext context =
                    reservationService.reserveOrReplay(
                            chatId,
                            request,
                            currentUser
                    );

            if (context.replay()) {
                return finalizationService.replaySucceeded(
                        context,
                        currentUser
                );
            }

            try {
                leaseWatch = leaseService.watch(
                        context.chatId(),
                        context.turnId(),
                        context.clientRequestId(),
                        context.processingToken()
                );
            } catch (ChatLeaseUnavailableException exception) {
                try {
                    finalizationService.failBeforeProviderCall(
                            context,
                            "LEASE_WATCHDOG_UNAVAILABLE",
                            "CHAT_LEASE_WATCHDOG_UNAVAILABLE",
                            currentUser
                    );
                } catch (RuntimeException failurePersistenceException) {
                    exception.addSuppressed(
                            failurePersistenceException
                    );
                }

                throw exception;
            }


            // Durable marker is committed before any provider HTTP attempt.
            finalizationService.markProviderCallStarted(context);

            AiChatResponse response = invokeProvider(context, currentUser);

            try {
                lockService.ensureValid(redisLock);
                leaseService.ensureValid(leaseWatch);
            } catch (ChatLockUnavailableException
                     | ChatStaleProcessorException exception) {
                markAmbiguousQuietly(
                        context,
                        response.requestedModel(),
                        response.providerRequestId(),
                        "PROCESSING_OWNERSHIP_LOST",
                        "CHAT_PROCESSING_OWNERSHIP_LOST",
                        currentUser,
                        exception
                );
                throw new AiOutcomeAmbiguousException(
                        context.chatId(),
                        context.turnId(),
                        context.clientRequestId(),
                        exception
                );
            }

            SendMessageResponse result;
            try {
                result = finalizationService.succeed(
                        context,
                        response,
                        currentUser
                );
            } catch (ChatStaleProcessorException exception) {
                throw new AiOutcomeAmbiguousException(
                        context.chatId(),
                        context.turnId(),
                        context.clientRequestId(),
                        exception
                );
            } catch (RuntimeException persistenceException) {
                try {
                    finalizationService.markAmbiguousAfterPersistenceFailure(
                            context,
                            response,
                            currentUser
                    );
                } catch (RuntimeException ambiguityPersistenceException) {
                    persistenceException.addSuppressed(
                            ambiguityPersistenceException
                    );
                }
                throw new AiOutcomeAmbiguousException(
                        context.chatId(),
                        context.turnId(),
                        context.clientRequestId(),
                        persistenceException
                );
            }

            securityStateService.assertStillActive(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    currentUser
            );
            return result;
        } finally {
            leaseService.close(leaseWatch);
            lockService.unlockQuietly(redisLock);
        }
    }

    @Transactional(readOnly = true)
    public Slice<MessageResponse> findMessages(
            UUID chatId,
            Pageable pageable,
            SafeAiUserPrincipal currentUser
    ) {
        findOwnedSession(chatId, currentUser);
        return messageRepository.findVisibleBySessionId(
                chatId,
                pageable
        ).map(mapper::toMessageResponse);
    }

    @Transactional(readOnly = true)
    public ChatTurnStatusResponse findTurnStatus(
            UUID chatId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        findOwnedSession(chatId, currentUser);
        return finalizationService.status(
                chatId,
                clientRequestId,
                currentUser
        );
    }

    private AiChatResponse invokeProvider(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            return aiProvider.sendMessage(context.aiRequest());
        } catch (AiProviderException exception) {
            if (exception.isOutcomeAmbiguous()) {
                markAmbiguousQuietly(
                        context,
                        exception.getModel(),
                        exception.getProviderRequestId(),
                        exception.getErrorType().name(),
                        "AI_PROVIDER_OUTCOME_AMBIGUOUS",
                        currentUser,
                        exception
                );
                throw new AiOutcomeAmbiguousException(
                        context.chatId(),
                        context.turnId(),
                        context.clientRequestId(),
                        exception
                );
            }

            try {
                finalizationService.failUnambiguous(
                        context,
                        exception,
                        currentUser
                );
            } catch (ChatStaleProcessorException staleProcessorException) {
                exception.addSuppressed(staleProcessorException);
                throw new AiOutcomeAmbiguousException(
                        context.chatId(),
                        context.turnId(),
                        context.clientRequestId(),
                        exception
                );
            } catch (RuntimeException finalizationException) {
                exception.addSuppressed(finalizationException);
            }
            throw new ChatTurnFailedException(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    "AI_PROVIDER_" + exception.getErrorType().name(),
                    exception
            );
        } catch (RuntimeException unexpectedProviderException) {
            // Conservative classification: provider invocation had started.
            markAmbiguousQuietly(
                    context,
                    null,
                    null,
                    unexpectedProviderException.getClass().getSimpleName(),
                    "AI_PROVIDER_UNCLASSIFIED_OUTCOME",
                    currentUser,
                    unexpectedProviderException
            );
            throw new AiOutcomeAmbiguousException(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    unexpectedProviderException
            );
        }
    }

    private void markAmbiguousQuietly(
            ChatProcessingContext context,
            String requestedModel,
            String providerRequestId,
            String providerErrorType,
            String failureCode,
            SafeAiUserPrincipal currentUser,
            RuntimeException primary
    ) {
        try {
            finalizationService.markAmbiguous(
                    context,
                    null,
                    requestedModel,
                    providerRequestId,
                    providerErrorType,
                    failureCode,
                    currentUser
            );
        } catch (RuntimeException exception) {
            primary.addSuppressed(exception);
            log.error(
                    "Failed to persist AMBIGUOUS chat turn: turnId={}",
                    context.turnId(),
                    exception
            );
        }
    }

    private void assertOwnedChatExists(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        if (!sessionRepository.existsByIdAndUser_IdAndOrganization_Id(
                chatId,
                currentUser.getId(),
                currentUser.getOrganizationId()
        )) {
            throw new ResourceNotFoundException("Чат не найден: " + chatId);
        }
    }

    private ChatSessionEntity findOwnedSession(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        return sessionRepository.findByIdAndUser_IdAndOrganization_Id(
                chatId,
                currentUser.getId(),
                currentUser.getOrganizationId()
        ).orElseThrow(() -> new ResourceNotFoundException(
                "Чат не найден: " + chatId
        ));
    }

    private static void requireCurrentUser(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        boolean superAdmin = currentUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SystemRole.SUPER_ADMIN.authority()::equals);

        boolean tenantChatRole = currentUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority ->
                        SystemRole.ADMIN.authority().equals(authority)
                                || SystemRole.USER.authority().equals(authority)
                );

        if (superAdmin || !tenantChatRole) {
            throw new ForbiddenOperationException(
                    "Платформенный администратор не имеет доступа к tenant-chat"
            );
        }
    }
}
