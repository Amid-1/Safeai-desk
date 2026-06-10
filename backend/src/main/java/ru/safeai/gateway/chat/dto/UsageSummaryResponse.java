package ru.safeai.gateway.chat.dto;

import java.math.BigDecimal;

public record UsageSummaryResponse(
        String model,
        Long messagesCount,
        Long inputTokens,
        Long outputTokens,
        BigDecimal costUsd
) {

    public UsageSummaryResponse {
        if (model == null || model.isBlank()) {
            model = "unknown";
        }

        if (messagesCount == null) {
            messagesCount = 0L;
        }

        if (inputTokens == null) {
            inputTokens = 0L;
        }

        if (outputTokens == null) {
            outputTokens = 0L;
        }

        if (costUsd == null) {
            costUsd = BigDecimal.ZERO;
        }
    }
}