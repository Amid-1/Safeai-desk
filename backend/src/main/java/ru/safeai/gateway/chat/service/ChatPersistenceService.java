package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ai.dto.AiMessage;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AuditEventService auditEventService;
    private final ChatMapper chatMapper;
    private final ChatProperties chatProperties;

    @Transactional(readOnly = true)
    public void assertOwnedChatExists(UUID chatId, SafeAiUserPrincipal currentUser) {
        boolean exists = chatSessionRepository.existsByIdAndUser_Id(
                chatId,
                currentUser.getId()
        );

        if (!exists) {
            throw new ResourceNotFoundException("Чат не найден: " + chatId);
        }
    }

    @Transactional
    public ChatProcessingContext saveUserMessageAndPrepareAiRequest(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);
        session.touch();

        List<AiMessage> historyBeforeNewMessage = chatMessageRepository
                .findBySession_IdOrderByCreatedAtDescIdDesc(
                        session.getId(),
                        PageRequest.of(0, chatProperties.effectiveHistoryMessageLimit())
                )
                .stream()
                .sorted(
                        Comparator.comparing(ChatMessageEntity::getCreatedAt)
                                .thenComparing(ChatMessageEntity::getId)
                )
                .map(message -> new AiMessage(
                        message.getRole().name(),
                        message.getContent()
                ))
                .toList();

        ChatMessageEntity userMessage = new ChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent(request.content());
        userMessage.setStatus(ChatMessageStatus.COMPLETED);

        ChatMessageEntity savedUserMessage = chatMessageRepository.save(userMessage);

        auditEventService.record(
                currentUser.getId(),
                currentUser.getOrganizationId(),
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
                savedUserMessage.getId(),
                aiRequest
        );
    }

    @Transactional
    public ChatDetailsResponse saveAssistantMessageAndReturnChat(
            UUID chatId,
            UUID userMessageId,
            AiChatResponse aiResponse,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);
        session.touch();

        assertOwnedUserMessageExists(
                userMessageId,
                session,
                currentUser
        );

        ChatMessageEntity assistantMessage = createAssistantMessage(session, aiResponse);
        ChatMessageEntity savedAssistantMessage = chatMessageRepository.save(assistantMessage);

        auditEventService.record(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_RECEIVED,
                aiResponseDetails(session, savedAssistantMessage, aiResponse)
        );

        List<MessageResponse> messages = chatMessageRepository
                .findBySession_IdOrderByCreatedAtAscIdAsc(session.getId())
                .stream()
                .map(chatMapper::toMessageResponse)
                .toList();

        return chatMapper.toChatDetailsResponse(session, messages);
    }

    @Transactional
    public void saveFailedAssistantMessage(
            UUID chatId,
            UUID userMessageId,
            RuntimeException exception,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session = findOwnedSession(chatId, currentUser);
        session.touch();

        assertOwnedUserMessageExists(
                userMessageId,
                session,
                currentUser
        );

        ChatMessageEntity failedAssistantMessage = new ChatMessageEntity();
        failedAssistantMessage.setSession(session);
        failedAssistantMessage.setRole(ChatMessageRole.ASSISTANT);
        failedAssistantMessage.setContent("Не удалось получить ответ от AI-провайдера");
        failedAssistantMessage.setStatus(ChatMessageStatus.FAILED);

        ChatMessageEntity saved = chatMessageRepository.save(failedAssistantMessage);

        auditEventService.record(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                AuditEventType.AI_RESPONSE_FAILED,
                Map.of(
                        "chatId", session.getId().toString(),
                        "messageId", saved.getId().toString(),
                        "userMessageId", userMessageId.toString(),
                        "error", exception.getClass().getSimpleName()
                )
        );
    }

    @Transactional
    public void markUserMessageFailed(
            UUID userMessageId,
            SafeAiUserPrincipal currentUser
    ) {
        ChatMessageEntity message = chatMessageRepository.findById(userMessageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Сообщение не найдено: " + userMessageId
                ));

        if (!message.getSession().getUser().getId().equals(currentUser.getId())) {
            throw new ResourceNotFoundException("Сообщение не найдено: " + userMessageId);
        }

        message.setStatus(ChatMessageStatus.FAILED);
    }

    private void assertOwnedUserMessageExists(
            UUID userMessageId,
            ChatSessionEntity session,
            SafeAiUserPrincipal currentUser
    ) {
        ChatMessageEntity userMessage = chatMessageRepository.findById(userMessageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Сообщение не найдено: " + userMessageId
                ));

        if (!userMessage.getSession().getId().equals(session.getId())
                || !userMessage.getSession().getUser().getId().equals(currentUser.getId())
                || userMessage.getRole() != ChatMessageRole.USER) {
            throw new ResourceNotFoundException("Сообщение не найдено: " + userMessageId);
        }
    }

    private ChatMessageEntity createAssistantMessage(
            ChatSessionEntity session,
            AiChatResponse aiResponse
    ) {
        ChatMessageEntity assistantMessage = new ChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent(aiResponse.content());
        assistantMessage.setModel(aiResponse.model());
        assistantMessage.setInputTokens(aiResponse.inputTokens());
        assistantMessage.setOutputTokens(aiResponse.outputTokens());
        assistantMessage.setCostUsd(aiResponse.costUsd());
        assistantMessage.setStatus(ChatMessageStatus.COMPLETED);

        return assistantMessage;
    }

    private Map<String, Object> aiResponseDetails(
            ChatSessionEntity session,
            ChatMessageEntity savedAssistantMessage,
            AiChatResponse aiResponse
    ) {
        Map<String, Object> details = new HashMap<>();
        details.put("chatId", session.getId().toString());
        details.put("messageId", savedAssistantMessage.getId().toString());
        details.put("model", aiResponse.model());
        details.put("inputTokens", aiResponse.inputTokens());
        details.put("outputTokens", aiResponse.outputTokens());
        details.put("costUsd", aiResponse.costUsd());

        return details;
    }

    private ChatSessionEntity findOwnedSession(UUID chatId, SafeAiUserPrincipal currentUser) {
        return chatSessionRepository.findByIdAndUser_Id(chatId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Чат не найден: " + chatId
                ));
    }
}