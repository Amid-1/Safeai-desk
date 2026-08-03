package ru.safeai.gateway.chat.quota;

import java.math.BigDecimal;

public record ChatQuotaConsumption(
        long requests,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd
) {
}
