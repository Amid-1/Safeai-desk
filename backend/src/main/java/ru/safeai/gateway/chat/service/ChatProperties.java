package ru.safeai.gateway.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.chat")
public record ChatProperties(
        Integer detailsMessageLimit,
        Integer historyMessageLimit
) {
    public int effectiveDetailsMessageLimit() {
        return detailsMessageLimit == null || detailsMessageLimit <= 0
                ? 50
                : Math.min(detailsMessageLimit, 200);
    }

    public int effectiveHistoryMessageLimit() {
        return historyMessageLimit == null || historyMessageLimit <= 0
                ? 30
                : Math.min(historyMessageLimit, 100);
    }
}