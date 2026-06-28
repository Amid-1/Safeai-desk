package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.UUID;

public record ChatProcessingContext(
        UUID chatId,
        UUID userMessageId,
        AiChatRequest aiRequest
) {

}