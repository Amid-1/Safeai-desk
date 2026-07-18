package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
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
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private static final String FAILED_ASSISTANT_MESSAGE =
            "Не удалось получить ответ от AI-провайдера";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AuditEventService auditEventService;
    private final ChatMapper chatMapper;
    private final ChatProperties chatProperties;
    private final AiHistoryBuilder aiHistoryBuilder;

    @Transactional(readOnly = true)
    public void assertOwnedChatExists(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        boolean exists =
                chatSessionRepository
                        .existsByIdAndUser_IdAndOrganization_Id(
                                chatId,
                                currentUser.getId(),
                                currentUser.getOrganizationId()
                        );

        if (!exists) {
            throw new ResourceNotFoundException(
                    "Чат не найден: " + chatId
            );
        }
    }

    @Transactional
    public ChatProcessingContext saveUserMessageAndPrepareAiRequest(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session =
                findOwnedSession(chatId, currentUser);

        UUID clientRequestId =
                resolveClientRequestId(
                        request.clientRequestId()
                );

        var existingUserMessage =
                chatMessageRepository
                        .findBySession_IdAndClientRequestIdAndRole(
                                session.getId(),
                                clientRequestId,
                                ChatMessageRole.USER
                        );

        if (existingUserMessage.isPresent()) {
            return processExistingUserMessage(
                    session,
                    existingUserMessage.get(),
                    clientRequestId
            );
        }

        session.touch();

        List<ChatMessageEntity> newestFirst =
                chatMessageRepository
                        .findCompletedHistoryForAi(
                                session.getId(),
                                PageRequest.of(
                                        0,
                                        chatProperties
                                                .effectiveHistoryMessageLimit()
                                )
                        );

        List<AiMessage> history =
                aiHistoryBuilder.build(
                        newestFirst,
                        chatProperties
                                .effectiveHistoryTokenBudget()
                );

        ChatMessageEntity userMessage =
                createUserMessage(
                        session,
                        request.content(),
                        clientRequestId
                );

        ChatMessageEntity savedUserMessage =
                chatMessageRepository.saveAndFlush(
                        userMessage
                );

        auditUserMessageSent(
                session,
                savedUserMessage,
                clientRequestId,
                request.content(),
                currentUser
        );

        AiChatRequest aiRequest = new AiChatRequest(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                session.getId(),
                request.content(),
                history
        );

        return new ChatProcessingContext(
                session.getId(),
                savedUserMessage.getId(),
                clientRequestId,
                aiRequest,
                false
        );
    }

    @Transactional(readOnly = true)
    public ChatDetailsResponse returnExistingResult(
            UUID chatId,
            UUID userMessageId,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session =
                findOwnedSession(chatId, currentUser);

        boolean completedAssistantExists =
                chatMessageRepository
                        .findFirstBySession_IdAndReplyToMessageIdAndRoleOrderByCreatedAtDescIdDesc(
                                session.getId(),
                                userMessageId,
                                ChatMessageRole.ASSISTANT
                        )
                        .filter(message ->
                                message.getStatus()
                                        == ChatMessageStatus.COMPLETED
                        )
                        .isPresent();

        if (!completedAssistantExists) {
            throw new ChatBusyException(
                    "Запрос ещё обрабатывается"
            );
        }

        return details(session);
    }

    @Transactional
    public ChatDetailsResponse saveAssistantMessageAndReturnChat(
            UUID chatId,
            UUID userMessageId,
            AiChatResponse aiResponse,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session =
                findOwnedSession(chatId, currentUser);

        session.touch();

        assertOwnedUserMessageExists(
                userMessageId,
                session,
                currentUser
        );

        ChatMessageEntity assistantMessage =
                createCompletedAssistantMessage(
                        session,
                        userMessageId,
                        aiResponse
                );

        ChatMessageEntity savedAssistantMessage =
                chatMessageRepository.saveAndFlush(
                        assistantMessage
                );

        auditEventService.record(
                currentUser.getId(),
                session.getOrganization().getId(),
                AuditEventType.AI_RESPONSE_RECEIVED,
                aiResponseDetails(
                        session,
                        savedAssistantMessage,
                        aiResponse,
                        userMessageId
                )
        );

        return details(session);
    }

    @Transactional
    public void saveFailedAssistantMessage(
            UUID chatId,
            UUID userMessageId,
            RuntimeException exception,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session =
                findOwnedSession(chatId, currentUser);

        session.touch();

        assertOwnedUserMessageExists(
                userMessageId,
                session,
                currentUser
        );

        ChatMessageEntity failedAssistantMessage =
                createFailedAssistantMessage(
                        session,
                        userMessageId
                );

        ChatMessageEntity savedAssistantMessage =
                chatMessageRepository.saveAndFlush(
                        failedAssistantMessage
                );

        auditEventService.record(
                currentUser.getId(),
                session.getOrganization().getId(),
                AuditEventType.AI_RESPONSE_FAILED,
                Map.of(
                        "chatId",
                        session.getId().toString(),
                        "messageId",
                        savedAssistantMessage
                                .getId()
                                .toString(),
                        "userMessageId",
                        userMessageId.toString(),
                        "error",
                        exception.getClass()
                                .getSimpleName()
                )
        );
    }

    private ChatProcessingContext processExistingUserMessage(
            ChatSessionEntity session,
            ChatMessageEntity userMessage,
            UUID clientRequestId
    ) {
        boolean completedAssistantExists =
                chatMessageRepository
                        .findFirstBySession_IdAndReplyToMessageIdAndRoleOrderByCreatedAtDescIdDesc(
                                session.getId(),
                                userMessage.getId(),
                                ChatMessageRole.ASSISTANT
                        )
                        .filter(message ->
                                message.getStatus()
                                        == ChatMessageStatus.COMPLETED
                        )
                        .isPresent();

        if (!completedAssistantExists) {
            throw new ChatBusyException(
                    "Запрос с таким clientRequestId уже обрабатывается"
            );
        }

        return new ChatProcessingContext(
                session.getId(),
                userMessage.getId(),
                clientRequestId,
                null,
                true
        );
    }

    private ChatMessageEntity createUserMessage(
            ChatSessionEntity session,
            String content,
            UUID clientRequestId
    ) {
        ChatMessageEntity message =
                new ChatMessageEntity();

        message.setSession(session);
        message.setOrganization(
                session.getOrganization()
        );
        message.setRole(ChatMessageRole.USER);
        message.setContent(content);
        message.setStatus(
                ChatMessageStatus.COMPLETED
        );
        message.setClientRequestId(
                clientRequestId
        );

        return message;
    }

    private ChatMessageEntity createCompletedAssistantMessage(
            ChatSessionEntity session,
            UUID userMessageId,
            AiChatResponse aiResponse
    ) {
        ChatMessageEntity message =
                new ChatMessageEntity();

        message.setSession(session);
        message.setOrganization(
                session.getOrganization()
        );
        message.setRole(
                ChatMessageRole.ASSISTANT
        );
        message.setStatus(
                ChatMessageStatus.COMPLETED
        );
        message.setContent(
                aiResponse.content()
        );
        message.setReplyToMessageId(
                userMessageId
        );

        message.setModel(
                aiResponse.model()
        );
        message.setProviderMessageId(
                aiResponse.providerMessageId()
        );
        message.setAiResponseStatus(
                aiResponse.responseStatus()
        );
        message.setFinishReason(
                aiResponse.finishReason()
        );

        message.setInputTokens(
                aiResponse.inputTokens()
        );
        message.setOutputTokens(
                aiResponse.outputTokens()
        );
        message.setUsageStatus(
                aiResponse.usageStatus()
        );

        message.setCostUsd(
                aiResponse.costUsd()
        );
        message.setPricingStatus(
                aiResponse.pricingStatus()
        );
        message.setCurrency(
                aiResponse.currency()
        );
        message.setPricingVersion(
                aiResponse.priceVersion()
        );
        message.setPricingCalculatedAt(
                aiResponse.pricingCalculatedAt()
        );

        return message;
    }

    private ChatMessageEntity createFailedAssistantMessage(
            ChatSessionEntity session,
            UUID userMessageId
    ) {
        ChatMessageEntity message =
                new ChatMessageEntity();

        message.setSession(session);
        message.setOrganization(
                session.getOrganization()
        );
        message.setRole(
                ChatMessageRole.ASSISTANT
        );
        message.setReplyToMessageId(
                userMessageId
        );
        message.setContent(
                FAILED_ASSISTANT_MESSAGE
        );
        message.setStatus(
                ChatMessageStatus.FAILED
        );

        return message;
    }

    private void auditUserMessageSent(
            ChatSessionEntity session,
            ChatMessageEntity savedUserMessage,
            UUID clientRequestId,
            String content,
            SafeAiUserPrincipal currentUser
    ) {
        auditEventService.record(
                currentUser.getId(),
                session.getOrganization().getId(),
                AuditEventType.CHAT_MESSAGE_SENT,
                Map.of(
                        "chatId",
                        session.getId().toString(),
                        "messageId",
                        savedUserMessage
                                .getId()
                                .toString(),
                        "clientRequestId",
                        clientRequestId.toString(),
                        "messageLength",
                        content.length()
                )
        );
    }

    private ChatDetailsResponse details(
            ChatSessionEntity session
    ) {
        List<MessageResponse> messages =
                chatMessageRepository
                        .findBySession_IdOrderByCreatedAtDescIdDesc(
                                session.getId(),
                                PageRequest.of(
                                        0,
                                        chatProperties
                                                .effectiveDetailsMessageLimit()
                                )
                        )
                        .stream()
                        .sorted(
                                Comparator
                                        .comparing(
                                                ChatMessageEntity::getCreatedAt
                                        )
                                        .thenComparing(
                                                ChatMessageEntity::getId
                                        )
                        )
                        .map(
                                chatMapper::toMessageResponse
                        )
                        .toList();

        return chatMapper.toChatDetailsResponse(
                session,
                messages
        );
    }

    private void assertOwnedUserMessageExists(
            UUID userMessageId,
            ChatSessionEntity session,
            SafeAiUserPrincipal currentUser
    ) {
        boolean exists =
                chatMessageRepository
                        .existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                                userMessageId,
                                session.getId(),
                                currentUser.getId(),
                                currentUser
                                        .getOrganizationId(),
                                ChatMessageRole.USER
                        );

        if (!exists) {
            throw new ResourceNotFoundException(
                    "Сообщение не найдено: "
                            + userMessageId
            );
        }
    }

    private Map<String, Object> aiResponseDetails(
            ChatSessionEntity session,
            ChatMessageEntity savedMessage,
            AiChatResponse response,
            UUID userMessageId
    ) {
        Map<String, Object> details =
                new HashMap<>();

        details.put(
                "chatId",
                session.getId().toString()
        );
        details.put(
                "messageId",
                savedMessage.getId().toString()
        );
        details.put(
                "userMessageId",
                userMessageId.toString()
        );
        details.put(
                "model",
                response.model()
        );
        details.put(
                "inputTokens",
                response.inputTokens()
        );
        details.put(
                "outputTokens",
                response.outputTokens()
        );
        details.put(
                "costUsd",
                response.costUsd()
        );
        details.put(
                "usageStatus",
                response.usageStatus().name()
        );
        details.put(
                "pricingStatus",
                response.pricingStatus().name()
        );

        if (response.providerMessageId() != null) {
            details.put(
                    "providerMessageId",
                    response.providerMessageId()
            );
        }

        if (response.finishReason() != null) {
            details.put(
                    "finishReason",
                    response.finishReason()
            );
        }

        return details;
    }

    private ChatSessionEntity findOwnedSession(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        return chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        chatId,
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Чат не найден: "
                                        + chatId
                        )
                );
    }

    private UUID resolveClientRequestId(
            UUID clientRequestId
    ) {
        return clientRequestId == null
                ? UUID.randomUUID()
                : clientRequestId;
    }
}
