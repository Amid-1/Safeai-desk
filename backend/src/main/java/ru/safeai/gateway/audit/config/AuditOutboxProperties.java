package ru.safeai.gateway.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.audit.outbox")
public record AuditOutboxProperties(
        Integer batchSize,
        Integer maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Integer maxErrorLength
) {
    public AuditOutboxProperties {
        batchSize = batchSize == null ? 100 : batchSize;
        maxAttempts = maxAttempts == null ? 10 : maxAttempts;
        initialBackoff = initialBackoff == null
                ? Duration.ofSeconds(2)
                : initialBackoff;
        maxBackoff = maxBackoff == null
                ? Duration.ofHours(1)
                : maxBackoff;
        maxErrorLength = maxErrorLength == null
                ? 1_000
                : maxErrorLength;

        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalStateException(
                    "safeai.audit.outbox.batch-size "
                            + "должен быть от 1 до 1000"
            );
        }

        if (maxAttempts <= 0 || maxAttempts > 100) {
            throw new IllegalStateException(
                    "safeai.audit.outbox.max-attempts "
                            + "должен быть от 1 до 100"
            );
        }

        if (initialBackoff.isZero()
                || initialBackoff.isNegative()) {
            throw new IllegalStateException(
                    "safeai.audit.outbox.initial-backoff "
                            + "должен быть положительным"
            );
        }

        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException(
                    "safeai.audit.outbox.max-backoff "
                            + "не может быть меньше initial-backoff"
            );
        }

        if (maxErrorLength < 100
                || maxErrorLength > 1_000) {
            throw new IllegalStateException(
                    "safeai.audit.outbox.max-error-length "
                            + "должен быть от 100 до 1000"
            );
        }
    }

    public int effectiveBatchSize() {
        return batchSize;
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

    public int effectiveMaxErrorLength() {
        return maxErrorLength;
    }
}
