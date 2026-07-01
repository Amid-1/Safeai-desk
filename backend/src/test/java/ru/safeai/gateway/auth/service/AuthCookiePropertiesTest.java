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
                null,
                null
        );

        assertThat(properties.secure()).isFalse();
        assertThat(properties.sameSite()).isEqualTo("Lax");
        assertThat(properties.accessTokenMaxAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenMaxAge()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.domain()).isNull();
        assertThat(properties.hasDomain()).isFalse();
    }

    @Test
    void constructor_shouldNormalizeSameSiteValue() {
        AuthCookieProperties properties = new AuthCookieProperties(
                true,
                "none",
                Duration.ofMinutes(10),
                Duration.ofDays(7),
                null
        );

        assertThat(properties.sameSite()).isEqualTo("None");
    }

    @Test
    void constructor_shouldRejectInvalidSameSiteValue() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                false,
                "Invalid",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                null
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
                Duration.ofDays(30),
                null
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
                Duration.ofMinutes(15),
                null
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
                Duration.ofSeconds(-1),
                null
        );

        assertThat(properties.accessTokenMaxAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenMaxAge()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void constructor_shouldNormalizeCookieDomain() {
        AuthCookieProperties properties = new AuthCookieProperties(
                true,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                ".Example.COM"
        );

        assertThat(properties.domain()).isEqualTo("example.com");
        assertThat(properties.hasDomain()).isTrue();
    }

    @Test
    void constructor_shouldRejectCookieDomainWithScheme() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                true,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "https://example.com"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain-only");
    }

    @Test
    void constructor_shouldRejectCookieDomainWithPort() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                true,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "example.com:8080"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain-only");
    }

    @Test
    void constructor_shouldRejectInvalidCookieDomain() {
        assertThatThrownBy(() -> new AuthCookieProperties(
                true,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "-example.com"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookies.domain");
    }
}