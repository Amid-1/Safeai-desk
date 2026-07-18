package ru.safeai.gateway.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.chat.lock")
public record ChatLockProperties(
        String keyPrefix,
        Duration ttl
) {
    private static final Duration MIN_TTL = Duration.ofSeconds(30);
    private static final Duration MAX_TTL = Duration.ofMinutes(30);

    public ChatLockProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalStateException("safeai.chat.lock.key-prefix не задан");
        }
        keyPrefix = normalizePrefix(keyPrefix);

        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("safeai.chat.lock.ttl должен быть положительным");
        }
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalStateException(
                    "safeai.chat.lock.ttl должен быть в диапазоне 30s–30m"
            );
        }
    }

    public String effectiveKeyPrefix() {
        return keyPrefix;
    }

    public Duration effectiveTtl() {
        return ttl;
    }

    public Duration renewalInterval() {
        long millis = Math.max(1_000L, ttl.toMillis() / 3L);
        return Duration.ofMillis(millis);
    }

    private static String normalizePrefix(String value) {
        String normalized = value.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalStateException("safeai.chat.lock.key-prefix не задан");
        }
        return normalized;
    }
}
