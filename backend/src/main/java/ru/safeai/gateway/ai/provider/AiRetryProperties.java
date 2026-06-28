package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.ai.retry")
public record AiRetryProperties(
        Boolean enabled,
        Integer maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff
) {

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public int effectiveMaxAttempts() {
        if (maxAttempts == null || maxAttempts <= 1) {
            return 2;
        }

        return Math.min(maxAttempts, 3);
    }

    public Duration effectiveInitialBackoff() {
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            return Duration.ofMillis(250);
        }

        return initialBackoff;
    }

    public Duration effectiveMaxBackoff() {
        if (maxBackoff == null || maxBackoff.isZero() || maxBackoff.isNegative()) {
            return Duration.ofSeconds(2);
        }

        return maxBackoff;
    }
}