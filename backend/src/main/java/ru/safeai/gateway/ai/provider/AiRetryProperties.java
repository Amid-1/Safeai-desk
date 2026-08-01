package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.ai.retry")
public record AiRetryProperties(
        Boolean enabled,
        Integer maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration maxRetryAfter,
        Duration totalTimeout
) {

    private static final int DEFAULT_MAX_ATTEMPTS =
            1;

    private static final int MIN_MAX_ATTEMPTS =
            1;

    private static final int MAX_MAX_ATTEMPTS =
            3;

    private static final Duration DEFAULT_INITIAL_BACKOFF =
            Duration.ofMillis(250);

    private static final Duration DEFAULT_MAX_BACKOFF =
            Duration.ofSeconds(2);

    private static final Duration DEFAULT_MAX_RETRY_AFTER =
            Duration.ofSeconds(10);

    private static final Duration DEFAULT_TOTAL_TIMEOUT =
            Duration.ofSeconds(65);

    private static final Duration MIN_BACKOFF =
            Duration.ofMillis(10);

    private static final Duration MAX_ALLOWED_BACKOFF =
            Duration.ofSeconds(30);

    private static final Duration MIN_RETRY_AFTER =
            Duration.ofSeconds(1);

    private static final Duration MAX_ALLOWED_RETRY_AFTER =
            Duration.ofMinutes(2);

    private static final Duration MIN_TOTAL_TIMEOUT =
            Duration.ofSeconds(1);

    private static final Duration MAX_TOTAL_TIMEOUT =
            Duration.ofMinutes(5);

    public AiRetryProperties {
        enabled =
                Boolean.TRUE.equals(enabled);

        maxAttempts =
                maxAttempts == null
                        ? DEFAULT_MAX_ATTEMPTS
                        : maxAttempts;

        initialBackoff =
                initialBackoff == null
                        ? DEFAULT_INITIAL_BACKOFF
                        : initialBackoff;

        maxBackoff =
                maxBackoff == null
                        ? DEFAULT_MAX_BACKOFF
                        : maxBackoff;

        maxRetryAfter =
                maxRetryAfter == null
                        ? DEFAULT_MAX_RETRY_AFTER
                        : maxRetryAfter;

        totalTimeout =
                totalTimeout == null
                        ? DEFAULT_TOTAL_TIMEOUT
                        : totalTimeout;

        validateMaxAttempts(maxAttempts);

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
                MIN_RETRY_AFTER,
                MAX_ALLOWED_RETRY_AFTER
        );

        validateDuration(
                totalTimeout,
                "total-timeout",
                MIN_TOTAL_TIMEOUT,
                MAX_TOTAL_TIMEOUT
        );

        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException(
                    "safeai.ai.retry.max-backoff не должен быть меньше "
                            + "safeai.ai.retry.initial-backoff"
            );
        }

        if (!enabled && maxAttempts != DEFAULT_MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "При safeai.ai.retry.enabled=false "
                            + "safeai.ai.retry.max-attempts должен быть равен 1"
            );
        }
    }

    public boolean isEnabled() {
        return enabled && maxAttempts > 1;
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

    public Duration effectiveTotalTimeout() {
        return totalTimeout;
    }

    private static void validateMaxAttempts(
            int maxAttempts
    ) {
        if (maxAttempts < MIN_MAX_ATTEMPTS
                || maxAttempts > MAX_MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "safeai.ai.retry.max-attempts должен быть "
                            + "в диапазоне от "
                            + MIN_MAX_ATTEMPTS
                            + " до "
                            + MAX_MAX_ATTEMPTS
            );
        }
    }

    private static void validateDuration(
            Duration value,
            String propertyName,
            Duration min,
            Duration max
    ) {
        if (value.compareTo(min) < 0
                || value.compareTo(max) > 0) {
            throw new IllegalStateException(
                    "safeai.ai.retry."
                            + propertyName
                            + " должен быть в диапазоне "
                            + min
                            + "–"
                            + max
            );
        }
    }
}