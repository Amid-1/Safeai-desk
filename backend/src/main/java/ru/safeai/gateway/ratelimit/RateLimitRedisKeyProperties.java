package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.rate-limit.redis")
public record RateLimitRedisKeyProperties(
        String keyPrefix
) {
    public String effectiveKeyPrefix() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return "safeai:local";
        }

        String normalized = keyPrefix.trim();

        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.isBlank() ? "safeai:local" : normalized;
    }
}