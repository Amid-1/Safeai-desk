package ru.safeai.gateway.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.audit.outbox")
public record AuditOutboxProperties(
        Integer batchSize
) {
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 1_000;

    public AuditOutboxProperties {
        if (batchSize == null) {
            batchSize = DEFAULT_BATCH_SIZE;
        }

        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "safeai.audit.outbox.batch-size должен быть от 1 до 1000"
            );
        }
    }

    public int effectiveBatchSize() {
        return batchSize;
    }
}
