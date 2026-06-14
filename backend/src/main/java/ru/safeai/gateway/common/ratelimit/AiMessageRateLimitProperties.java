package ru.safeai.gateway.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.rate-limit.ai-messages")
public record AiMessageRateLimitProperties(
        boolean enabled,
        int userLimitPerHour,
        int adminLimitPerHour
) {
    public AiMessageRateLimitProperties {
        if (userLimitPerHour <= 0) {
            userLimitPerHour = 20;
        }

        if (adminLimitPerHour <= 0) {
            adminLimitPerHour = 100;
        }
    }
}