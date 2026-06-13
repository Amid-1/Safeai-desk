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

    public ChatResponse toChatResponse(ChatSessionEntity entity) {
        return new ChatResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt()
        );
    }

    public MessageResponse toMessageResponse(ChatMessageEntity entity) {
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

    public ChatDetailsResponse toChatDetailsResponse(
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