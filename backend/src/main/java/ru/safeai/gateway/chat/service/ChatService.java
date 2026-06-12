package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.AiChatResponse;
import ru.safeai.gateway.ai.AiProvider;
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
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final AiProvider aiProvider;
    private final AuditEventService auditEventService;
    private final ChatPersistenceService chatPersistenceService;

    @Transactional
    public ChatResponse create(CreateChatRequest request, SafeAiUserPrincipal currentUser) {
        UserEntity user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + currentUser.getId()
                ));

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUser(user);
        session.setTitle(normalizeTitle(request.title()));
        session.setCreatedAt(Instant.now());

        ChatSessionEntity saved = chatSessionRepository.save(session);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.CHAT_CREATED,
                Map.of(
                        "chatId", saved.getId().toString(),
                        "title", saved.getTitle()
                )
        );

        return toChatResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ChatResponse> findAll(SafeAiUserPrincipal currentUser) {
        return chatSessionRepository.findByUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(this::toChatResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatDetailsResponse findById(UUID chatId, SafeAiUserPrincipal currentUser) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        List<MessageResponse> messages = chatMessageRepository
                .findBySession_IdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(this::toMessageResponse)
                .toList();

        return toChatDetailsResponse(session, messages);
    }

    public ChatDetailsResponse sendMessage(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ChatProcessingContext context =
                chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                        chatId,
                        request,
                        currentUser
                );

        AiChatResponse aiResponse = aiProvider.sendMessage(context.aiRequest());

        return chatPersistenceService.saveAssistantMessageAndReturnChat(
                context.chatId(),
                aiResponse,
                currentUser
        );
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

    private ChatResponse toChatResponse(ChatSessionEntity entity) {
        return new ChatResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt()
        );
    }

    private MessageResponse toMessageResponse(ChatMessageEntity entity) {
        return new MessageResponse(
                entity.getId(),
                entity.getRole(),
                entity.getContent(),
                entity.getModel(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getCostUsd(),
                entity.getCreatedAt()
        );
    }

    private ChatDetailsResponse toChatDetailsResponse(
            ChatSessionEntity session,
            List<MessageResponse> messages
    ) {
        return new ChatDetailsResponse(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                messages
        );
    }
}