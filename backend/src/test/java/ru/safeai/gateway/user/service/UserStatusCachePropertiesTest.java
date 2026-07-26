package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserStatusCachePropertiesTest {

    @Test
    void constructorNormalizesPrefix() {
        UserStatusCacheProperties properties =
                new UserStatusCacheProperties(
                        true,
                        Duration.ofSeconds(15),
                        " safeai:prod:user-status "
                );

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.effectiveTtl())
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.effectiveKeyPrefix())
                .isEqualTo("safeai:prod:user-status:");
    }

    @Test
    void constructorUsesFailClosedDefaults() {
        UserStatusCacheProperties properties =
                new UserStatusCacheProperties(
                        null,
                        null,
                        null
                );

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.effectiveTtl())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.effectiveKeyPrefix())
                .isEqualTo("safeai:user-status:");
    }

    @Test
    void constructorRejectsInvalidTtl() {
        assertThatThrownBy(() ->
                new UserStatusCacheProperties(
                        true,
                        Duration.ZERO,
                        "safeai:test:user-status"
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("положительным");

        assertThatThrownBy(() ->
                new UserStatusCacheProperties(
                        true,
                        Duration.ofSeconds(-1),
                        "safeai:test:user-status"
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("положительным");

        assertThatThrownBy(() ->
                new UserStatusCacheProperties(
                        true,
                        Duration.ofMinutes(6),
                        "safeai:test:user-status"
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5 минут");
    }
}
