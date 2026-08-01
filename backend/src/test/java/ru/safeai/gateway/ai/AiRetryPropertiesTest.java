package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.AiRetryProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AiRetryPropertiesTest {

    @Test
    void productionSafeDefaultsDisableRetries() {
        AiRetryProperties properties = new AiRetryProperties(
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.effectiveMaxAttempts()).isEqualTo(1);
        assertThat(properties.effectiveInitialBackoff())
                .isEqualTo(Duration.ofMillis(250));
        assertThat(properties.effectiveMaxBackoff())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.effectiveMaxRetryAfter())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.effectiveTotalTimeout())
                .isEqualTo(Duration.ofSeconds(65));
    }

    @Test
    void enabledWithOneAttemptStillDoesNotRetry() {
        AiRetryProperties properties = new AiRetryProperties(
                true,
                1,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofSeconds(65)
        );

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.effectiveMaxAttempts()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidAttemptsBackoffAndDeadline() {
        assertThatThrownBy(() -> new AiRetryProperties(
                true,
                0,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofSeconds(65)
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new AiRetryProperties(
                true,
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofSeconds(65)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-backoff");

        assertThatThrownBy(() -> new AiRetryProperties(
                true,
                2,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                Duration.ofSeconds(10),
                Duration.ofMillis(100)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total-timeout");
    }
}
