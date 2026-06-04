package ru.safeai.gateway.ai;

import java.util.List;
import java.util.UUID;

public record AiChatRequest(
        UUID userId,
        UUID organizationId,
        UUID chatId,
        String userMessage,
        List<AiMessage> history
) {
}
