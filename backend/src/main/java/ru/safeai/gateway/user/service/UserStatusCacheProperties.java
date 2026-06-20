package ru.safeai.gateway.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.security.user-status-cache")
public record UserStatusCacheProperties(
        Boolean enabled,
        Duration ttl
) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public Duration effectiveTtl() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofSeconds(60);
        }

        return ttl;
    }
}