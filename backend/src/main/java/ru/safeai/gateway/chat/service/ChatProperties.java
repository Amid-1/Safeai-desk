package ru.safeai.gateway.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.chat")
public record ChatProperties(
        Integer detailsMessageLimit,
        Integer historyMessageLimit,
        Integer historyTokenBudget
) {
    public int effectiveDetailsMessageLimit() {
        return detailsMessageLimit == null || detailsMessageLimit <= 0 ? 50 : Math.min(detailsMessageLimit, 200);
    }
    public int effectiveHistoryMessageLimit() {
        return historyMessageLimit == null || historyMessageLimit <= 0 ? 30 : Math.min(historyMessageLimit, 100);
    }
    public int effectiveHistoryTokenBudget() {
        return historyTokenBudget == null || historyTokenBudget <= 0 ? 8_000 : Math.min(historyTokenBudget, 100_000);
    }
}
