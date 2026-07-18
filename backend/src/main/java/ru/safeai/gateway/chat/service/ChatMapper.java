package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;

import java.util.List;

@Component
public class ChatMapper {

    public ChatResponse toChatResponse(
            ChatSessionEntity entity
    ) {
        return new ChatResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public MessageResponse toMessageResponse(
            ChatMessageEntity entity
    ) {
        return new MessageResponse(
                entity.getId(),
                entity.getRole().name(),
                entity.getContent(),
                entity.getModel(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getUsageStatus().name(),
                entity.getCostUsd(),
                entity.getPricingStatus().name(),
                entity.getCurrency(),
                entity.getPricingVersion(),
                entity.getPricingCalculatedAt(),
                entity.getCreatedAt(),
                entity.getStatus().name()
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
}
