package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void validPropertiesAreKept() {
        AiMessageRateLimitProperties ai =
                new AiMessageRateLimitProperties(
                        true,
                        20,
                        100,
                        1_000
                );

        LoginRateLimitProperties login =
                new LoginRateLimitProperties(
                        true,
                        10,
                        30,
                        Duration.ofMinutes(10)
                );

        assertThat(ai.effectiveUserLimitPerHour())
                .isEqualTo(20);
        assertThat(login.effectiveWindow())
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void invalidAiLimitsFailFast() {
        assertThatThrownBy(() ->
                new AiMessageRateLimitProperties(
                        true,
                        0,
                        100,
                        1_000
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new AiMessageRateLimitProperties(
                        true,
                        20,
                        100,
                        1_000_001
                )
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidLoginLimitsAndWindowFailFast() {
        assertThatThrownBy(() ->
                new LoginRateLimitProperties(
                        true,
                        0,
                        30,
                        Duration.ofMinutes(10)
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new LoginRateLimitProperties(
                        true,
                        10,
                        30,
                        Duration.ofHours(25)
                )
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redisPrefixIsNormalizedAndRequired() {
        RateLimitRedisKeyProperties properties =
                new RateLimitRedisKeyProperties(
                        " safeai:prod::: "
                );

        assertThat(properties.effectiveKeyPrefix())
                .isEqualTo("safeai:prod");

        assertThatThrownBy(() ->
                new RateLimitRedisKeyProperties(" ")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-prefix");
    }
}
