package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.AiChatRequest;
import ru.safeai.gateway.ai.AiChatResponse;
import ru.safeai.gateway.ai.AiMessage;
import ru.safeai.gateway.ai.AiProvider;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final AiProvider aiProvider;
    private final AuditEventService auditEventService;

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

        ChatMessageEntity savedUserMessage = chatMessageRepository.save(userMessage);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.CHAT_MESSAGE_SENT,
                Map.of(
                        "chatId", session.getId().toString(),
                        "messageId", savedUserMessage.getId().toString(),
                        "messageLength", request.content().length()
                )
        );

        List<AiMessage> history = chatMessageRepository
                .findBySession_IdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(message -> new AiMessage(
                        message.getRole(),
                        message.getContent()
                ))
                .toList();

        AiChatRequest aiRequest = new AiChatRequest(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                session.getId(),
                request.content(),
                history
        );

        AiChatResponse aiResponse = aiProvider.sendMessage(aiRequest);

        ChatMessageEntity assistantMessage = new ChatMessageEntity();
        assistantMessage.setId(UUID.randomUUID());
        assistantMessage.setSession(session);
        assistantMessage.setRole(ROLE_ASSISTANT);
        assistantMessage.setContent(aiResponse.content());
        assistantMessage.setModel(aiResponse.model());
        assistantMessage.setInputTokens(aiResponse.inputTokens());
        assistantMessage.setOutputTokens(aiResponse.outputTokens());
        assistantMessage.setCostUsd(aiResponse.costUsd());
        assistantMessage.setCreatedAt(LocalDateTime.now());

        ChatMessageEntity savedAssistantMessage = chatMessageRepository.save(assistantMessage);

        Map<String, Object> aiResponseDetails = new HashMap<>();
        aiResponseDetails.put("chatId", session.getId().toString());
        aiResponseDetails.put("messageId", savedAssistantMessage.getId().toString());
        aiResponseDetails.put("model", aiResponse.model());
        aiResponseDetails.put("inputTokens", aiResponse.inputTokens());
        aiResponseDetails.put("outputTokens", aiResponse.outputTokens());
        aiResponseDetails.put("costUsd", aiResponse.costUsd());

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.AI_RESPONSE_RECEIVED,
                aiResponseDetails
        );

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