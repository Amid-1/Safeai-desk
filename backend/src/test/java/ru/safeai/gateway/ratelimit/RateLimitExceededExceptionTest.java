package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.RateLimitExceededException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceededExceptionTest {

    @Test
    void retryAfterRoundsUpWithoutMillisecondOverflow() {
        assertThat(new RateLimitExceededException(
                "limit",
                Duration.ofNanos(1)
        ).getRetryAfterSeconds()).isEqualTo(1);

        assertThat(new RateLimitExceededException(
                "limit",
                Duration.ofMillis(1_001)
        ).getRetryAfterSeconds()).isEqualTo(2);

        assertThat(new RateLimitExceededException(
                "limit",
                Duration.ofSeconds(10)
        ).getRetryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void nonPositiveRetryAfterProducesNoHeaderValue() {
        assertThat(new RateLimitExceededException(
                "limit",
                null
        ).getRetryAfterSeconds()).isZero();

        assertThat(new RateLimitExceededException(
                "limit",
                Duration.ZERO
        ).getRetryAfterSeconds()).isZero();

        assertThat(new RateLimitExceededException(
                "limit",
                Duration.ofSeconds(-1)
        ).getRetryAfterSeconds()).isZero();
    }
}
