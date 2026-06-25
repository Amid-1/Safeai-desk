package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookiePropertiesTest {

    @Test
    void constructor_shouldUseDefaultsWhenValuesAreMissing() {
        AuthCookieProperties properties = new AuthCookieProperties(
                false,
                null,
                null,
                null
        );

        assertThat(properties.secure()).isFalse();
        assertThat(properties.sameSite()).isEqualTo("Lax");
        assertThat(properties.accessTokenMaxAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenMaxAge()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void constructor_shouldNormalizeSameSiteValue() {
        AuthCookieProperties properties = new AuthCookieProperties(
                true,
                "none",
                Duration.ofMinutes(10),
                Duration.ofDays(7)
        );

        assertThat(properties.sameSite()).isEqualTo("None");
    }

    @Test
    void constructor_shouldRejectInvalidSameSiteValue() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                false,
                "Invalid",
                Duration.ofMinutes(15),
                Duration.ofDays(30)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same-site");
    }

    @Test
    void constructor_shouldRejectSameSiteNoneWithoutSecure() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                false,
                "None",
                Duration.ofMinutes(15),
                Duration.ofDays(30)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Secure=true");
    }

    @Test
    void constructor_shouldRejectRefreshMaxAgeLessThanAccessMaxAge() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                false,
                "Lax",
                Duration.ofDays(30),
                Duration.ofMinutes(15)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-token-max-age");
    }

    @Test
    void constructor_shouldReplaceInvalidDurationsWithDefaults() {
        AuthCookieProperties properties = new AuthCookieProperties(
                false,
                "Lax",
                Duration.ZERO,
                Duration.ofSeconds(-1)
        );

        assertThat(properties.accessTokenMaxAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenMaxAge()).isEqualTo(Duration.ofDays(30));
    }
}