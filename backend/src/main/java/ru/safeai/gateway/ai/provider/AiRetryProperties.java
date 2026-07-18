package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.ai.retry")
public record AiRetryProperties(
        Boolean enabled,
        Integer maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration maxRetryAfter
) {
    private static final Duration MIN_BACKOFF =
            Duration.ofMillis(10);
    private static final Duration MAX_ALLOWED_BACKOFF =
            Duration.ofSeconds(30);
    private static final Duration MAX_ALLOWED_RETRY_AFTER =
            Duration.ofMinutes(2);

    public AiRetryProperties {
        enabled = enabled == null || enabled;
        maxAttempts = maxAttempts == null ? 2 : maxAttempts;
        initialBackoff = initialBackoff == null
                ? Duration.ofMillis(250)
                : initialBackoff;
        maxBackoff = maxBackoff == null
                ? Duration.ofSeconds(2)
                : maxBackoff;
        maxRetryAfter = maxRetryAfter == null
                ? Duration.ofSeconds(10)
                : maxRetryAfter;

        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalStateException(
                    "safeai.ai.retry.max-attempts должен быть от 1 до 3"
            );
        }

        validateDuration(
                initialBackoff,
                "initial-backoff",
                MIN_BACKOFF,
                MAX_ALLOWED_BACKOFF
        );
        validateDuration(
                maxBackoff,
                "max-backoff",
                MIN_BACKOFF,
                MAX_ALLOWED_BACKOFF
        );
        validateDuration(
                maxRetryAfter,
                "max-retry-after",
                Duration.ofSeconds(1),
                MAX_ALLOWED_RETRY_AFTER
        );

        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException(
                    "safeai.ai.retry.max-backoff не должен быть меньше initial-backoff"
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int effectiveMaxAttempts() {
        return maxAttempts;
    }

    public Duration effectiveInitialBackoff() {
        return initialBackoff;
    }

    public Duration effectiveMaxBackoff() {
        return maxBackoff;
    }

    public Duration effectiveMaxRetryAfter() {
        return maxRetryAfter;
    }

    private static void validateDuration(
            Duration value,
            String name,
            Duration min,
            Duration max
    ) {
        if (value.isNegative()
                || value.isZero()
                || value.compareTo(min) < 0
                || value.compareTo(max) > 0) {
            throw new IllegalStateException(
                    "safeai.ai.retry." + name
                            + " должен быть в диапазоне "
                            + min + "–" + max
            );
        }
    }
}
