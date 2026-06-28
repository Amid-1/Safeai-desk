package ru.safeai.gateway.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.chat.lock")
public record ChatLockProperties(
        String keyPrefix,
        Duration ttl
) {
    public String effectiveKeyPrefix() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return "safeai:local:chat-lock";
        }

        String normalized = keyPrefix.trim();

        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.isBlank()
                ? "safeai:local:chat-lock"
                : normalized;
    }

    public Duration effectiveTtl() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofSeconds(120);
        }

        return ttl;
    }
}