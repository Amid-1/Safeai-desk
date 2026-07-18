package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.rate-limit.redis")
public record RateLimitRedisKeyProperties(
        String keyPrefix
) {
    public RateLimitRedisKeyProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalStateException(
                    "safeai.rate-limit.redis.key-prefix не задан"
            );
        }

        keyPrefix = keyPrefix.trim();

        while (keyPrefix.endsWith(":")) {
            keyPrefix = keyPrefix.substring(
                    0,
                    keyPrefix.length() - 1
            );
        }

        if (keyPrefix.isBlank()) {
            throw new IllegalStateException(
                    "safeai.rate-limit.redis.key-prefix не задан"
            );
        }
    }

    public String effectiveKeyPrefix() {
        return keyPrefix;
    }
}
