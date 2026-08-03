package ru.safeai.gateway.usage.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsagePropertiesTest {

    @Test
    void defaultsAreBoundedAndProductionSafe() {
        UsageProperties properties = new UsageProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.defaultRange())
                .isEqualTo(Duration.ofDays(30));
        assertThat(properties.maxRange())
                .isEqualTo(Duration.ofDays(366));
        assertThat(properties.maxLiveRange())
                .isEqualTo(Duration.ofDays(31));
        assertThat(properties.maxPageSize()).isEqualTo(200);
        assertThat(properties.queryTimeout())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.slowQueryThreshold())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.maxConcurrentReports()).isEqualTo(4);
        assertThat(properties.rollup().statementTimeout())
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void subSecondJdbcTimeoutIsRejected() {
        assertThatThrownBy(() -> new UsageProperties(
                null,
                null,
                null,
                null,
                Duration.ofMillis(500),
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не меньше 1 секунды");
    }

    @Test
    void pageSizeCannotExceedPublicApiLimit() {
        assertThatThrownBy(() -> new UsageProperties(
                null,
                null,
                null,
                201,
                null,
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-page-size");
    }

    @Test
    void slowQueryThresholdCannotExceedTimeout() {
        assertThatThrownBy(() -> new UsageProperties(
                null,
                null,
                null,
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(6),
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slow-query-threshold");
    }
    @Test
    void durationSecondsAreRoundedUpForJdbcConfiguration() {
        UsageProperties properties = new UsageProperties(
                null,
                null,
                null,
                null,
                Duration.ofMillis(1500),
                Duration.ofSeconds(1),
                null,
                null,
                new UsageProperties.Rollup(
                        true,
                        null,
                        null,
                        null,
                        null,
                        Duration.ofMillis(1500)
                )
        );

        assertThat(properties.queryTimeoutSeconds()).isEqualTo(2);
        assertThat(properties.rollupStatementTimeoutSeconds())
                .isEqualTo(2);
    }

    @Test
    void defaultAndLiveRangesCannotExceedMaximumRange() {
        assertThatThrownBy(() -> new UsageProperties(
                Duration.ofDays(31),
                Duration.ofDays(30),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-range");

        assertThatThrownBy(() -> new UsageProperties(
                null,
                Duration.ofDays(30),
                Duration.ofDays(31),
                null,
                null,
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-live-range");
    }

    @Test
    void concurrencyFetchAndRollupBoundsAreValidated() {
        assertThatThrownBy(() -> new UsageProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                10_001,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fetch-size");

        assertThatThrownBy(() -> new UsageProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                101,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-reports");

        assertThatThrownBy(() -> new UsageProperties.Rollup(
                true,
                null,
                32,
                31,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lookback-days");
    }

}
