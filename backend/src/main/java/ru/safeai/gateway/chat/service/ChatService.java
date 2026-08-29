package ru.safeai.gateway.chat.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagService;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-chat application facade.
 *
 * <p>CRUD/read transactions stay on this Spring bean. Long-running turn
 * execution is delegated to {@link ChatTurnExecutionCoordinator}, which keeps
 * provider I/O outside a database transaction while preserving the existing
 * lock, lease, fencing, idempotency and AMBIGUOUS semantics.</p>
 */
@Service
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AuditEventService auditEventService;
    private final ChatTurnFinalizationService finalizationService;
    private final ChatMapper mapper;
    private final ChatProperties properties;
    private final Clock clock;
    private final ChatTurnExecutionCoordinator turnExecution;

    /**
     * Constructor signature intentionally remains unchanged for Spring wiring
     * and existing unit tests.
     */
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
            Clock clock,
            KnowledgeRagService ragService
    ) {
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository,
                "sessionRepository не должен быть null"
        );
        this.messageRepository = Objects.requireNonNull(
                messageRepository,
                "messageRepository не должен быть null"
        );
        this.userRepository = Objects.requireNonNull(
                userRepository,
                "userRepository не должен быть null"
        );
        this.auditEventService = Objects.requireNonNull(
                auditEventService,
                "auditEventService не должен быть null"
        );
        this.finalizationService = Objects.requireNonNull(
                finalizationService,
                "finalizationService не должен быть null"
        );
        this.mapper = Objects.requireNonNull(mapper, "mapper не должен быть null");
        this.properties = Objects.requireNonNull(properties, "properties не должен быть null");
        this.clock = Objects.requireNonNull(clock, "clock не должен быть null");
        this.turnExecution = new ChatTurnExecutionCoordinator(
                aiProvider,
                reservationService,
                finalizationService,
                securityStateService,
                lockService,
                leaseService,
                ragService
        );
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
                        "defaultTitle", request.title() == null || request.title().isBlank()
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

        return sessionRepository
                .findByUser_IdAndOrganization_IdAndArchivedAtIsNull(
                        currentUser.getId(),
                        currentUser.getOrganizationId(),
                        pageable
                )
                .map(mapper::toChatResponse);
    }

    @Transactional
    public void archive(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);
        Instant archivedAt = clock.instant();

        session.archive(currentUser.getId(), archivedAt);
        sessionRepository.saveAndFlush(session);

        auditEventService.record(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.CHAT_ARCHIVED,
                Map.of(
                        "chatId", session.getId(),
                        "archivedAt", archivedAt.toString(),
                        "retentionMode", "AUDIT_PRESERVED"
                )
        );
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
                        PageRequest.of(
                                0,
                                properties.detailsMessageLimit(),
                                Sort.by(
                                        Sort.Order.desc("createdAt"),
                                        Sort.Order.desc("id")
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
        return turnExecution.execute(chatId, request, currentUser);
    }

    @Transactional(readOnly = true)
    public Slice<MessageResponse> findMessages(
            UUID chatId,
            Pageable pageable,
            SafeAiUserPrincipal currentUser
    ) {
        findOwnedSession(chatId, currentUser);

        return messageRepository
                .findVisibleBySessionId(chatId, pageable)
                .map(mapper::toMessageResponse);
    }

    @Transactional(readOnly = true)
    public ChatTurnStatusResponse findTurnStatus(
            UUID chatId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        findOwnedSession(chatId, currentUser);
        return finalizationService.status(chatId, clientRequestId, currentUser);
    }

    private void assertOwnedChatExists(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        if (!sessionRepository
                .existsByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
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

        return sessionRepository
                .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                        chatId,
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Чат не найден: " + chatId
                ));
    }

    private static void requireCurrentUser(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        boolean superAdmin = currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SystemRole.SUPER_ADMIN.authority()::equals);

        boolean tenantChatRole = currentUser.getAuthorities()
                .stream()
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
