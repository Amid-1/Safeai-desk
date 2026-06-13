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
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.HashMap;
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
    private final ChatMapper chatMapper;

    @Transactional
    public ChatResponse create(CreateChatRequest request, SafeAiUserPrincipal currentUser) {
        UserEntity user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + currentUser.getId()
                ));

        ChatSessionEntity session = new ChatSessionEntity();
        session.setUser(user);
        session.setTitle(normalizeTitle(request.title()));

        ChatSessionEntity saved = chatSessionRepository.save(session);

        Map<String, Object> details = new HashMap<>();
        details.put("chatId", saved.getId().toString());
        details.put("title", saved.getTitle());

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.CHAT_CREATED,
                details
        );

        return chatMapper.toChatResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ChatResponse> findAll(SafeAiUserPrincipal currentUser) {
        return chatSessionRepository.findByUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(chatMapper::toChatResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatDetailsResponse findById(UUID chatId, SafeAiUserPrincipal currentUser) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        List<MessageResponse> messages = chatMessageRepository
                .findBySession_IdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(chatMapper::toMessageResponse)
                .toList();

        return chatMapper.toChatDetailsResponse(session, messages);
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

        try {
            AiChatResponse aiResponse = aiProvider.sendMessage(context.aiRequest());

            return chatPersistenceService.saveAssistantMessageAndReturnChat(
                    context.chatId(),
                    aiResponse,
                    currentUser
            );
        } catch (RuntimeException exception) {
            auditEventService.record(
                    currentUser.getId(),
                    AuditEventType.AI_RESPONSE_FAILED,
                    Map.of(
                            "chatId", context.chatId().toString(),
                            "error", exception.getClass().getSimpleName()
                    )
            );

            throw exception;
        }
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
}