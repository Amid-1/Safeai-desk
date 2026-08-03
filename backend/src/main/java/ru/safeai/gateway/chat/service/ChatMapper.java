package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.ChatTurnStatusResponse;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;

import java.util.List;

@Component
public class ChatMapper {

    public ChatResponse toChatResponse(ChatSessionEntity entity) {
        return new ChatResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public MessageResponse toMessageResponse(ChatMessageEntity entity) {
        return new MessageResponse(
                entity.getId(),
                entity.getClientRequestId(),
                entity.getReplyToMessageId(),
                enumName(entity.getRole()),
                enumName(entity.getStatus()),
                entity.getContent(),
                entity.getRequestedModel(),
                entity.getModel(),
                entity.getProviderMessageId(),
                entity.getProviderRequestId(),
                enumName(entity.getAiResponseStatus()),
                entity.getFinishReason(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                enumName(entity.getUsageStatus()),
                entity.getCostUsd(),
                enumName(entity.getPricingStatus()),
                entity.getCurrency(),
                entity.getPricingVersion(),
                entity.getPricingCalculatedAt(),
                entity.getCreatedAt()
        );
    }

    public ChatDetailsResponse toChatDetailsResponse(
            ChatSessionEntity entity,
            List<MessageResponse> messages
    ) {
        return new ChatDetailsResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                messages
        );
    }

    public ChatTurnStatusResponse toTurnStatusResponse(
            ChatTurnEntity turn,
            MessageResponse userMessage,
            MessageResponse assistantMessage
    ) {
        return new ChatTurnStatusResponse(
                turn.getSession().getId(),
                turn.getId(),
                turn.getClientRequestId(),
                turn.getProviderOperationId(),
                turn.getState().name(),
                turn.getProvider(),
                turn.getRequestedModel(),
                turn.getResolvedModel(),
                turn.getProviderRequestId(),
                turn.getProviderErrorType(),
                turn.getFailureCode(),
                turn.isOutcomeAmbiguous(),
                turn.getLeaseUntil(),
                turn.getProviderCallStartedAt(),
                turn.getCreatedAt(),
                turn.getUpdatedAt(),
                turn.getCompletedAt(),
                userMessage,
                assistantMessage
        );
    }

    private static String enumName(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Enum<?> enumValue
                ? enumValue.name()
                : value.toString();
    }
}
