package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.rate-limit.login")
public record LoginRateLimitProperties(
        Boolean enabled,
        Integer emailLimit,
        Integer ipLimit,
        Duration window
) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public int effectiveEmailLimit() {
        return emailLimit == null || emailLimit <= 0 ? 10 : emailLimit;
    }

    public int effectiveIpLimit() {
        return ipLimit == null || ipLimit <= 0 ? 30 : ipLimit;
    }

    public Duration effectiveWindow() {
        if (window == null || window.isZero() || window.isNegative()) {
            return Duration.ofMinutes(10);
        }

        return window;
    }
}