package ru.safeai.gateway.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.chat.lock")
public record ChatLockProperties(
        String keyPrefix,
        Duration ttl,
        Integer renewalThreads,
        Integer maxActiveLocks,
        Duration shutdownTimeout
) {
    private static final Duration MIN_TTL = Duration.ofSeconds(30);
    private static final Duration MAX_TTL = Duration.ofMinutes(30);

    public ChatLockProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalStateException(
                    "safeai.chat.lock.key-prefix не задан"
            );
        }
        keyPrefix = normalizePrefix(keyPrefix);
        ttl = ttl == null ? Duration.ofMinutes(4) : ttl;
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalStateException(
                    "safeai.chat.lock.ttl должен быть в диапазоне 30s–30m"
            );
        }
        renewalThreads = renewalThreads == null ? 4 : renewalThreads;
        if (renewalThreads < 1 || renewalThreads > 32) {
            throw new IllegalStateException(
                    "safeai.chat.lock.renewal-threads должен быть в диапазоне 1–32"
            );
        }
        maxActiveLocks = maxActiveLocks == null ? 1_000 : maxActiveLocks;
        if (maxActiveLocks < 1 || maxActiveLocks > 100_000) {
            throw new IllegalStateException(
                    "safeai.chat.lock.max-active-locks должен быть в диапазоне 1–100000"
            );
        }
        shutdownTimeout = shutdownTimeout == null
                ? Duration.ofSeconds(5)
                : shutdownTimeout;
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalStateException(
                    "safeai.chat.lock.shutdown-timeout должен быть положительным"
            );
        }
    }

    public Duration renewalInterval() {
        return Duration.ofMillis(
                Math.max(1_000L, ttl.toMillis() / 3L)
        );
    }

    private static String normalizePrefix(String value) {
        String normalized = value.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalStateException(
                    "safeai.chat.lock.key-prefix не задан"
            );
        }
        return normalized;
    }
}
