package ru.safeai.gateway.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.security.user-status-cache")
public record UserStatusCacheProperties(
        Boolean enabled,
        Duration ttl,
        String keyPrefix
) {
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final String DEFAULT_KEY_PREFIX =
            "safeai:user-status:";

    public UserStatusCacheProperties {
        if (enabled == null) {
            enabled = false;
        }

        if (ttl == null) {
            ttl = DEFAULT_TTL;
        }

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException(
                    "safeai.security.user-status-cache.ttl должен быть положительным"
            );
        }

        if (ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalStateException(
                    "safeai.security.user-status-cache.ttl не должен превышать 5 минут"
            );
        }

        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = DEFAULT_KEY_PREFIX;
        }

        keyPrefix = keyPrefix.trim();

        if (!keyPrefix.endsWith(":")) {
            keyPrefix = keyPrefix + ":";
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public Duration effectiveTtl() {
        return ttl;
    }

    public String effectiveKeyPrefix() {
        return keyPrefix;
    }
}
