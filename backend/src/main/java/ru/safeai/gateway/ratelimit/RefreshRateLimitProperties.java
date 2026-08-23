package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(
        prefix = "safeai.rate-limit.refresh"
)
public record RefreshRateLimitProperties(
        Boolean enabled,
        Integer ipLimit,
        Duration window
) {

    private static final int DEFAULT_IP_LIMIT = 600;
    private static final Duration DEFAULT_WINDOW =
            Duration.ofMinutes(1);

    private static final int MAX_LIMIT = 1_000_000;
    private static final Duration MAX_WINDOW =
            Duration.ofHours(24);

    public RefreshRateLimitProperties {
        enabled = enabled == null || enabled;

        ipLimit = ipLimit == null
                ? DEFAULT_IP_LIMIT
                : ipLimit;

        window = window == null
                ? DEFAULT_WINDOW
                : window;

        if (ipLimit < 1 || ipLimit > MAX_LIMIT) {
            throw new IllegalStateException(
                    "safeai.rate-limit.refresh.ip-limit "
                            + "должен быть в диапазоне 1–"
                            + MAX_LIMIT
            );
        }

        if (window.isZero()
                || window.isNegative()
                || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalStateException(
                    "safeai.rate-limit.refresh.window "
                            + "должен быть положительным "
                            + "и не превышать 24 часа"
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int effectiveIpLimit() {
        return ipLimit;
    }

    public Duration effectiveWindow() {
        return window;
    }
}
