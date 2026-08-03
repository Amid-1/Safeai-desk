package ru.safeai.gateway.chat.quota;

import java.math.BigDecimal;

public record ChatQuotaPolicy(
        boolean enabled,
        Long monthlyRequestLimit,
        Long monthlyInputTokenLimit,
        Long monthlyOutputTokenLimit,
        BigDecimal monthlyCostLimitUsd
) {
    public static ChatQuotaPolicy unlimited() {
        return new ChatQuotaPolicy(false, null, null, null, null);
    }
}
