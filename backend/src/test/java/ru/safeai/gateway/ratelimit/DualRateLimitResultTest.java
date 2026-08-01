package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DualRateLimitResultTest {

    @Test
    void allowedResultHasZeroRetryAfter() {
        DualRateLimitResult result =
                new DualRateLimitResult(
                        RateLimitDecision.ALLOWED,
                        1,
                        2,
                        10,
                        20,
                        false,
                        false
                );

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfterSeconds())
                .isZero();
        assertThat(result.notificationRequired())
                .isFalse();
    }

    @Test
    void retryAfterUsesOnlyExceededDimensions() {
        assertThat(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                10,
                20,
                30,
                90,
                false,
                false
        ).retryAfterSeconds()).isEqualTo(30);

        assertThat(new DualRateLimitResult(
                RateLimitDecision.SECOND_EXCEEDED,
                10,
                20,
                30,
                90,
                false,
                false
        ).retryAfterSeconds()).isEqualTo(90);

        assertThat(new DualRateLimitResult(
                RateLimitDecision.BOTH_EXCEEDED,
                10,
                20,
                30,
                90,
                false,
                false
        ).retryAfterSeconds()).isEqualTo(90);
    }

    @Test
    void notificationFlagsMustMatchDecision() {
        assertThatThrownBy(() ->
                new DualRateLimitResult(
                        RateLimitDecision.ALLOWED,
                        1,
                        1,
                        10,
                        10,
                        true,
                        false
                )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new DualRateLimitResult(
                        RateLimitDecision.FIRST_EXCEEDED,
                        1,
                        1,
                        10,
                        10,
                        false,
                        true
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countersAndTtlCannotBeNegative() {
        assertThatThrownBy(() ->
                new DualRateLimitResult(
                        RateLimitDecision.ALLOWED,
                        -1,
                        1,
                        10,
                        10,
                        false,
                        false
                )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new DualRateLimitResult(
                        RateLimitDecision.ALLOWED,
                        1,
                        1,
                        -1,
                        10,
                        false,
                        false
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
