package ru.safeai.gateway.chat.title;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.AutomaticChatTitleRequest;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.UpdateChatTitleRequest;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.repository.ChatTurnMutexRepository;
import ru.safeai.gateway.chat.service.ChatMapper;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatTitleService {

    private final ChatSessionRepository sessionRepository;
    private final ChatTurnMutexRepository mutexRepository;
    private final AuditEventService auditEventService;
    private final ChatMapper mapper;
    private final Clock clock;

    public ChatTitleService(
            ChatSessionRepository sessionRepository,
            ChatTurnMutexRepository mutexRepository,
            AuditEventService auditEventService,
            ChatMapper mapper,
            Clock clock
    ) {
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository не должен быть null"
        );
        this.mutexRepository = Objects.requireNonNull(
                mutexRepository, "mutexRepository не должен быть null"
        );
        this.auditEventService = Objects.requireNonNull(
                auditEventService, "auditEventService не должен быть null"
        );
        this.mapper = Objects.requireNonNull(
                mapper, "mapper не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock, "clock не должен быть null"
        );
    }

    /**
     * Unlike auto-title assignment inside ChatTurn reservation, this path
     * does not need an AI turn to succeed. The first submitted user message
     * claims the title even when the AI route rejects the request.
     * <p>
     * No raw text is copied into audit. Explicit USER titles always win.
     */
    @Transactional
    public ChatResponse assignAutomaticTitle(
            UUID chatId,
            AutomaticChatTitleRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        // Service-layer validation too: not only HTTP Bean Validation.
        if (request.content() == null || request.content().isBlank()
                || request.content().length() > 16000) {
            throw new BadRequestException("Текст первого сообщения некорректен");
        }
        // Same lock order as ChatTurn reservation and manual rename.
        mutexRepository.lockChat(chatId);
        ChatSessionEntity session = sessionRepository
                .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                        chatId, currentUser.getId(), currentUser.getOrganizationId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Чат не найден: " + chatId
                ));
        if (session.getTitleSource() != ChatTitleSource.DEFAULT) {
            return mapper.toChatResponse(session);
        }
        // Validates Unicode/control characters and truncates to 72 code points.
        String title = ChatTitlePolicy.automaticTitle(request.content());
        session.assignAutomaticTitle(title, clock.instant());
        return mapper.toChatResponse(sessionRepository.saveAndFlush(session));
    }

    @Transactional
    public ChatResponse rename(
            UUID chatId,
            UpdateChatTitleRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        /*
         * Serialize manual rename with first-message reservation.
         * Whichever happens later still preserves explicit USER intent.
         */
        mutexRepository.lockChat(chatId);

        ChatSessionEntity session = sessionRepository
                .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
                        chatId,
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Чат не найден: " + chatId
                ));

        String normalized = ChatTitlePolicy.normalizeManualTitle(request.title());
        ChatTitleSource previousSource = session.getTitleSource();
        String previousTitle = session.getTitle();

        /*
         * A repeated identical USER rename is an idempotent no-op.
         * If the text is identical to AUTO, we must still transition to USER,
         * because that explicit action owns the title from now on.
         */
        if (normalized.equals(previousTitle)
                && previousSource == ChatTitleSource.USER) {
            return mapper.toChatResponse(session);
        }

        Instant now = clock.instant();
        session.renameByUser(normalized, now);
        ChatSessionEntity saved = sessionRepository.saveAndFlush(session);

        AuditDetails auditDetails = () -> Map.of(
                "chatId", saved.getId(),
                "previousSource", previousSource == null
                        ? "UNKNOWN" : previousSource.name(),
                "newSource", ChatTitleSource.USER.name(),
                "previousTitleLength", codePointLength(previousTitle),
                "newTitleLength", codePointLength(saved.getTitle())
        );

        auditEventService.recordPrincipalSnapshot(
                currentUser,
                currentUser.getOrganizationId(),
                AuditEventType.CHAT_RENAMED,
                auditDetails
        );

        return mapper.toChatResponse(saved);
    }

    private static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
