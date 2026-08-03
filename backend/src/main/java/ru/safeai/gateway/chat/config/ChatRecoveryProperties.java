package ru.safeai.gateway.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

@ConfigurationProperties(prefix = "safeai.chat.recovery")
public record ChatRecoveryProperties(
        Boolean enabled,
        Integer batchSize,
        Integer maxBatchesPerRun,
        String cron
) {

    private static final boolean DEFAULT_ENABLED = true;

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_MAX_BATCHES_PER_RUN = 20;

    private static final int MIN_BATCH_PARAMETER_VALUE = 1;
    private static final int MAX_BATCH_PARAMETER_VALUE = 1_000;

    private static final String DEFAULT_CRON =
            "0 * * * * *";

    public ChatRecoveryProperties {
        enabled = enabled == null
                ? DEFAULT_ENABLED
                : enabled;

        batchSize = boundedBatchParameter(
                batchSize,
                DEFAULT_BATCH_SIZE,
                "batch-size"
        );

        maxBatchesPerRun = boundedBatchParameter(
                maxBatchesPerRun,
                DEFAULT_MAX_BATCHES_PER_RUN,
                "max-batches-per-run"
        );

        cron = normalizeAndValidateCron(cron);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    private static int boundedBatchParameter(
            Integer value,
            int defaultValue,
            String propertyName
    ) {
        int resolved = value == null
                ? defaultValue
                : value;

        if (resolved < MIN_BATCH_PARAMETER_VALUE
                || resolved > MAX_BATCH_PARAMETER_VALUE) {
            throw new IllegalStateException(
                    "safeai.chat.recovery."
                            + propertyName
                            + " должен быть в диапазоне "
                            + MIN_BATCH_PARAMETER_VALUE
                            + "–"
                            + MAX_BATCH_PARAMETER_VALUE
            );
        }

        return resolved;
    }

    private static String normalizeAndValidateCron(
            String value
    ) {
        String normalized = value == null || value.isBlank()
                ? DEFAULT_CRON
                : value.trim();

        try {
            CronExpression.parse(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "safeai.chat.recovery.cron содержит "
                            + "некорректное Spring cron-выражение: "
                            + normalized,
                    exception
            );
        }

        return normalized;
    }
}