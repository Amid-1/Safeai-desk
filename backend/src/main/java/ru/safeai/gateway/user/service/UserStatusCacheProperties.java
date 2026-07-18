package ru.safeai.gateway.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.security.user-status-cache")
public record UserStatusCacheProperties(
        Boolean enabled,
        Duration ttl,
        String keyPrefix
) {
    private static final Duration MAX_TTL = Duration.ofMinutes(5);

    public UserStatusCacheProperties {
        if (enabled == null) {
            enabled = true;
        }

        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
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
            throw new IllegalStateException(
                    "safeai.security.user-status-cache.key-prefix не задан"
            );
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
