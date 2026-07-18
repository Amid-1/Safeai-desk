package ru.safeai.gateway.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.audit.retention")
public record AuditRetentionProperties(
        Boolean enabled,
        Duration period,
        Integer batchSize,
        Integer maxBatchesPerRun
) {
    private static final Duration MIN_PERIOD = Duration.ofDays(30);
    private static final Duration MAX_PERIOD = Duration.ofDays(3_650);

    public AuditRetentionProperties {
        enabled = enabled == null || enabled;

        if (period == null
                || period.compareTo(MIN_PERIOD) < 0
                || period.compareTo(MAX_PERIOD) > 0) {
            throw new IllegalStateException(
                    "safeai.audit.retention.period должен быть "
                            + "в диапазоне 30d–3650d"
            );
        }

        if (batchSize == null
                || batchSize < 100
                || batchSize > 100_000) {
            throw new IllegalStateException(
                    "safeai.audit.retention.batch-size должен быть "
                            + "в диапазоне 100–100000"
            );
        }

        if (maxBatchesPerRun == null
                || maxBatchesPerRun < 1
                || maxBatchesPerRun > 1_000) {
            throw new IllegalStateException(
                    "safeai.audit.retention.max-batches-per-run "
                            + "должен быть в диапазоне 1–1000"
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration effectivePeriod() {
        return period;
    }

    public int effectiveBatchSize() {
        return batchSize;
    }

    public int effectiveMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }
}
