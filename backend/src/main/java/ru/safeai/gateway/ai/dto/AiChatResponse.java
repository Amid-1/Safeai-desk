package ru.safeai.gateway.ai.dto;

import java.math.BigDecimal;

public record AiChatResponse(
        String content,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd
) {

    public AiChatResponse {
        content = content == null ? "" : content;
        model = model == null || model.isBlank() ? "unknown" : model;
        inputTokens = inputTokens == null ? 0 : Math.max(0, inputTokens);
        outputTokens = outputTokens == null ? 0 : Math.max(0, outputTokens);
        costUsd = costUsd == null || costUsd.signum() < 0
                ? BigDecimal.ZERO
                : costUsd;
    }
}