package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.ai.provider.AiProvider;

import java.util.*;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatProperties.class)
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final AiProvider aiProvider;
    private final AuditEventService auditEventService;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatMapper chatMapper;
    private final RedisRateLimitService rateLimitService;
    private final ChatLockService chatLockService;
    private final ChatProperties chatProperties;

    @Transactional
    public ChatResponse create(CreateChatRequest request, SafeAiUserPrincipal currentUser) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        UserEntity user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + currentUser.getId()
                ));

        String normalizedTitle = normalizeTitle(request.title());
        boolean defaultTitle = request.title() == null || request.title().isBlank();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setUser(user);
        session.setOrganization(user.getOrganization());
        session.setTitle(normalizedTitle);

        ChatSessionEntity saved = chatSessionRepository.save(session);

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.CHAT_CREATED,
                Map.of(
                        "chatId", saved.getId().toString(),
                        "titleLength", saved.getTitle() == null ? 0 : saved.getTitle().length(),
                        "defaultTitle", defaultTitle
                )
        );

        return chatMapper.toChatResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ChatResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        Objects.requireNonNull(pageable, "pageable не должен быть null");

        return chatSessionRepository
                .findByUser_IdOrderByUpdatedAtDesc(currentUser.getId(), pageable)
                .map(chatMapper::toChatResponse);
    }

    @Transactional(readOnly = true)
    public ChatDetailsResponse findById(UUID chatId, SafeAiUserPrincipal currentUser) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        List<MessageResponse> messages = chatMessageRepository
                .findBySession_IdOrderByCreatedAtDescIdDesc(
                        session.getId(),
                        PageRequest.of(0, chatProperties.effectiveDetailsMessageLimit())
                )
                .stream()
                .sorted(
                        Comparator.comparing(ChatMessageEntity::getCreatedAt)
                                .thenComparing(ChatMessageEntity::getId)
                )
                .map(chatMapper::toMessageResponse)
                .toList();

        return chatMapper.toChatDetailsResponse(session, messages);
    }

    public ChatDetailsResponse sendMessage(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        String normalizedContent = normalizeMessageContent(request.content());

        chatPersistenceService.assertOwnedChatExists(chatId, currentUser);

        ChatLockService.ChatLock lock = chatLockService.lock(chatId);

        try {
            rateLimitService.checkAiMessageAllowed(currentUser);

            ChatProcessingContext context =
                    chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                            chatId,
                            new SendMessageRequest(normalizedContent),
                            currentUser
                    );

            try {
                AiChatResponse aiResponse = aiProvider.sendMessage(context.aiRequest());

                return chatPersistenceService.saveAssistantMessageAndReturnChat(
                        context.chatId(),
                        context.userMessageId(),
                        aiResponse,
                        currentUser
                );
            } catch (RuntimeException exception) {
                chatPersistenceService.saveFailedAssistantMessage(
                        context.chatId(),
                        context.userMessageId(),
                        exception,
                        currentUser
                );

                throw exception;
            }
        } finally {
            chatLockService.unlockQuietly(lock);
        }
    }

    @Transactional(readOnly = true)
    public Page<MessageResponse> findMessages(
            UUID chatId,
            int page,
            int size,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        return chatMessageRepository
                .findBySession_IdOrderByCreatedAtAscIdAsc(
                        session.getId(),
                        PageRequest.of(safePage, safeSize)
                )
                .map(chatMapper::toMessageResponse);
    }

    private ChatSessionEntity findOwnedSession(UUID chatId, SafeAiUserPrincipal currentUser) {
        return chatSessionRepository.findByIdAndUser_Id(chatId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Чат не найден: " + chatId
                ));
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Новый чат";
        }

        return title.trim();
    }

    private String normalizeMessageContent(String content) {
        if (content == null || content.trim().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Сообщение не должно быть пустым"
            );
        }

        return content.trim();
    }
}