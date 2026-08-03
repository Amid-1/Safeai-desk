package ru.safeai.gateway.usage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.usage")
public record UsageProperties(
        Duration defaultRange,
        Duration maxRange,
        Duration maxLiveRange,
        Integer maxPageSize,
        Duration queryTimeout,
        Duration slowQueryThreshold,
        Integer fetchSize,
        Integer maxConcurrentReports,
        Rollup rollup
) {

    private static final int MIN_POSITIVE_INT = 1;

    private static final int DEFAULT_MAX_PAGE_SIZE = 200;
    private static final int MAX_API_PAGE_SIZE = 200;

    private static final int DEFAULT_FETCH_SIZE = 200;
    private static final int MAX_FETCH_SIZE = 10_000;

    private static final int DEFAULT_MAX_CONCURRENT_REPORTS = 4;
    private static final int MAX_CONCURRENT_REPORTS = 100;

    private static final Duration DEFAULT_RANGE =
            Duration.ofDays(30);

    private static final Duration DEFAULT_MAX_RANGE =
            Duration.ofDays(366);

    private static final Duration DEFAULT_MAX_LIVE_RANGE =
            Duration.ofDays(31);

    private static final Duration DEFAULT_QUERY_TIMEOUT =
            Duration.ofSeconds(10);

    private static final Duration DEFAULT_SLOW_QUERY_THRESHOLD =
            Duration.ofSeconds(2);

    private static final Duration MIN_QUERY_TIMEOUT =
            Duration.ofSeconds(1);

    public UsageProperties {
        defaultRange = positiveDuration(
                defaultRange,
                DEFAULT_RANGE,
                "default-range"
        );

        maxRange = positiveDuration(
                maxRange,
                DEFAULT_MAX_RANGE,
                "max-range"
        );

        if (defaultRange.compareTo(maxRange) > 0) {
            throw new IllegalStateException(
                    "safeai.usage.default-range не должен превышать "
                            + "safeai.usage.max-range"
            );
        }

        maxLiveRange = positiveDuration(
                maxLiveRange,
                DEFAULT_MAX_LIVE_RANGE,
                "max-live-range"
        );

        if (maxLiveRange.compareTo(maxRange) > 0) {
            throw new IllegalStateException(
                    "safeai.usage.max-live-range не должен превышать "
                            + "safeai.usage.max-range"
            );
        }

        maxPageSize = boundedPositiveInt(
                maxPageSize,
                DEFAULT_MAX_PAGE_SIZE,
                MAX_API_PAGE_SIZE,
                "max-page-size"
        );

        queryTimeout = atLeastOneSecond(
                queryTimeout,
                DEFAULT_QUERY_TIMEOUT,
                "query-timeout"
        );

        slowQueryThreshold = positiveDuration(
                slowQueryThreshold,
                DEFAULT_SLOW_QUERY_THRESHOLD,
                "slow-query-threshold"
        );

        if (slowQueryThreshold.compareTo(queryTimeout) > 0) {
            throw new IllegalStateException(
                    "safeai.usage.slow-query-threshold не должен "
                            + "превышать safeai.usage.query-timeout"
            );
        }

        fetchSize = boundedPositiveInt(
                fetchSize,
                DEFAULT_FETCH_SIZE,
                MAX_FETCH_SIZE,
                "fetch-size"
        );

        maxConcurrentReports = boundedPositiveInt(
                maxConcurrentReports,
                DEFAULT_MAX_CONCURRENT_REPORTS,
                MAX_CONCURRENT_REPORTS,
                "max-concurrent-reports"
        );

        rollup = rollup == null
                ? Rollup.defaults()
                : rollup;
    }

    public int queryTimeoutSeconds() {
        return durationSecondsCeil(
                queryTimeout,
                "safeai.usage.query-timeout"
        );
    }

    public int rollupStatementTimeoutSeconds() {
        return durationSecondsCeil(
                rollup.statementTimeout(),
                "safeai.usage.rollup.statement-timeout"
        );
    }

    private static Duration positiveDuration(
            Duration value,
            Duration defaultValue,
            String propertyName
    ) {
        Duration resolved = value == null
                ? defaultValue
                : value;

        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalStateException(
                    "safeai.usage."
                            + propertyName
                            + " должен быть положительным"
            );
        }

        return resolved;
    }

    private static Duration atLeastOneSecond(
            Duration value,
            Duration defaultValue,
            String propertyName
    ) {
        Duration resolved = positiveDuration(
                value,
                defaultValue,
                propertyName
        );

        if (resolved.compareTo(MIN_QUERY_TIMEOUT) < 0) {
            throw new IllegalStateException(
                    "safeai.usage."
                            + propertyName
                            + " должен быть не меньше 1 секунды"
            );
        }

        return resolved;
    }

    private static int boundedPositiveInt(
            Integer value,
            int defaultValue,
            int maxValue,
            String propertyName
    ) {
        int resolved = value == null
                ? defaultValue
                : value;

        if (resolved < MIN_POSITIVE_INT
                || resolved > maxValue) {
            throw new IllegalStateException(
                    "safeai.usage."
                            + propertyName
                            + " должен находиться в диапазоне "
                            + MIN_POSITIVE_INT
                            + ".."
                            + maxValue
            );
        }

        return resolved;
    }

    private static int durationSecondsCeil(
            Duration duration,
            String propertyName
    ) {
        long seconds = duration.getSeconds();

        if (duration.getNano() > 0) {
            seconds = Math.addExact(
                    seconds,
                    1L
            );
        }

        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    propertyName + " слишком велик"
            );
        }

        return Math.toIntExact(seconds);
    }

    public record Rollup(
            Boolean enabled,
            String cron,
            Integer lookbackDays,
            Integer backfillDaysPerRun,
            Long advisoryLockKey,
            Duration statementTimeout
    ) {

        private static final boolean DEFAULT_ENABLED = true;

        private static final String DEFAULT_CRON =
                "0 */10 * * * *";

        private static final int DEFAULT_LOOKBACK_DAYS = 3;
        private static final int MAX_LOOKBACK_DAYS = 31;

        private static final int DEFAULT_BACKFILL_DAYS_PER_RUN = 31;
        private static final int MAX_BACKFILL_DAYS_PER_RUN = 366;

        private static final long DEFAULT_ADVISORY_LOCK_KEY =
                8_026_001L;

        private static final Duration DEFAULT_STATEMENT_TIMEOUT =
                Duration.ofMinutes(2);

        public Rollup {
            enabled = enabled == null
                    ? DEFAULT_ENABLED
                    : enabled;

            cron = cron == null || cron.isBlank()
                    ? DEFAULT_CRON
                    : cron.trim();

            lookbackDays = boundedPositiveInt(
                    lookbackDays,
                    DEFAULT_LOOKBACK_DAYS,
                    MAX_LOOKBACK_DAYS,
                    "rollup.lookback-days"
            );

            backfillDaysPerRun = boundedPositiveInt(
                    backfillDaysPerRun,
                    DEFAULT_BACKFILL_DAYS_PER_RUN,
                    MAX_BACKFILL_DAYS_PER_RUN,
                    "rollup.backfill-days-per-run"
            );

            advisoryLockKey = advisoryLockKey == null
                    ? DEFAULT_ADVISORY_LOCK_KEY
                    : advisoryLockKey;

            statementTimeout = atLeastOneSecond(
                    statementTimeout,
                    DEFAULT_STATEMENT_TIMEOUT,
                    "rollup.statement-timeout"
            );
        }

        public static Rollup defaults() {
            return new Rollup(
                    DEFAULT_ENABLED,
                    DEFAULT_CRON,
                    DEFAULT_LOOKBACK_DAYS,
                    DEFAULT_BACKFILL_DAYS_PER_RUN,
                    DEFAULT_ADVISORY_LOCK_KEY,
                    DEFAULT_STATEMENT_TIMEOUT
            );
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }
}