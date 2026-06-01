package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.*;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String MOCK_MODEL = "mock-safeai";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

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
        session.setCreatedAt(LocalDateTime.now());

        ChatSessionEntity saved = chatSessionRepository.save(session);

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

    @Transactional
    public ChatDetailsResponse sendMessage(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        ChatMessageEntity userMessage = new ChatMessageEntity();
        userMessage.setId(UUID.randomUUID());
        userMessage.setSession(session);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(request.content());
        userMessage.setModel(null);
        userMessage.setInputTokens(null);
        userMessage.setOutputTokens(null);
        userMessage.setCostUsd(null);
        userMessage.setCreatedAt(LocalDateTime.now());

        chatMessageRepository.save(userMessage);

        ChatMessageEntity assistantMessage = new ChatMessageEntity();
        assistantMessage.setId(UUID.randomUUID());
        assistantMessage.setSession(session);
        assistantMessage.setRole(ROLE_ASSISTANT);
        assistantMessage.setContent(generateMockAnswer(request.content()));
        assistantMessage.setModel(MOCK_MODEL);
        assistantMessage.setInputTokens(null);
        assistantMessage.setOutputTokens(null);
        assistantMessage.setCostUsd(null);
        assistantMessage.setCreatedAt(LocalDateTime.now());

        chatMessageRepository.save(assistantMessage);

        List<MessageResponse> messages = chatMessageRepository
                .findBySession_IdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(this::toMessageResponse)
                .toList();

        return toChatDetailsResponse(session, messages);
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

    private String generateMockAnswer(String userMessage) {
        return "Mock AI response: " + userMessage;
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