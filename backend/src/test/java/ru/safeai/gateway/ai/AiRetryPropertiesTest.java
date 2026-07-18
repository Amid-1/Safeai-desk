package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.AiRetryProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRetryPropertiesTest {

    @Test
    void maxAttemptsOneMeansExactlyOneAttempt() {
        AiRetryProperties properties = new AiRetryProperties(
                true,
                1,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
        );

        assertThat(properties.effectiveMaxAttempts()).isEqualTo(1);
    }

    @Test
    void appliesDefaults() {
        AiRetryProperties properties = new AiRetryProperties(
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.effectiveMaxAttempts()).isEqualTo(2);
        assertThat(properties.effectiveInitialBackoff())
                .isEqualTo(Duration.ofMillis(250));
        assertThat(properties.effectiveMaxBackoff())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.effectiveMaxRetryAfter())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsInvalidAttemptsAndBackoffOrder() {
        assertThatThrownBy(() -> new AiRetryProperties(
                true,
                0,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new AiRetryProperties(
                true,
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-backoff");
    }
}
