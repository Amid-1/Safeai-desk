package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.AiChatRequest;
import ru.safeai.gateway.ai.AiChatResponse;
import ru.safeai.gateway.ai.AiMessage;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AuditEventService auditEventService;

    @Transactional
    public ChatProcessingContext saveUserMessageAndPrepareAiRequest(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        List<AiMessage> historyBeforeNewMessage = chatMessageRepository
                .findBySession_IdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(message -> new AiMessage(
                        message.getRole(),
                        message.getContent()
                ))
                .toList();

        ChatMessageEntity userMessage = new ChatMessageEntity();
        userMessage.setId(UUID.randomUUID());
        userMessage.setSession(session);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(request.content());
        userMessage.setModel(null);
        userMessage.setInputTokens(null);
        userMessage.setOutputTokens(null);
        userMessage.setCostUsd(null);
        userMessage.setCreatedAt(Instant.now());

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

        AiChatRequest aiRequest = new AiChatRequest(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                session.getId(),
                request.content(),
                historyBeforeNewMessage
        );

        return new ChatProcessingContext(
                session.getId(),
                aiRequest
        );
    }

    @Transactional
    public ChatDetailsResponse saveAssistantMessageAndReturnChat(
            UUID chatId,
            AiChatResponse aiResponse,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);

        ChatMessageEntity assistantMessage = new ChatMessageEntity();
        assistantMessage.setId(UUID.randomUUID());
        assistantMessage.setSession(session);
        assistantMessage.setRole(ROLE_ASSISTANT);
        assistantMessage.setContent(aiResponse.content());
        assistantMessage.setModel(aiResponse.model());
        assistantMessage.setInputTokens(aiResponse.inputTokens());
        assistantMessage.setOutputTokens(aiResponse.outputTokens());
        assistantMessage.setCostUsd(aiResponse.costUsd());
        assistantMessage.setCreatedAt(Instant.now());

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