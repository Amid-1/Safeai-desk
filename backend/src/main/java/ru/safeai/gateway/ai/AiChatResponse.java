package ru.safeai.gateway.ai;

import java.math.BigDecimal;

public record AiChatResponse(
        String content,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd
) {
}
