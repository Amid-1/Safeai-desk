package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.ai.AiChatRequest;

import java.util.UUID;

public record ChatProcessingContext(
        UUID chatId,
        UUID userMessageId,
        AiChatRequest aiRequest
) {

}