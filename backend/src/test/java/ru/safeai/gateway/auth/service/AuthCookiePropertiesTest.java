package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookiePropertiesTest {

    @Test
    void constructorPreservesValidValues() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        null
                );

        assertThat(properties.secure()).isFalse();
        assertThat(properties.sameSite())
                .isEqualTo("Lax");
        assertThat(properties.accessTokenMaxAge())
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenMaxAge())
                .isEqualTo(Duration.ofDays(30));
        assertThat(properties.domain()).isNull();
        assertThat(properties.hasDomain()).isFalse();
    }

    @Test
    void constructorNormalizesSameSiteValue() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        true,
                        "none",
                        Duration.ofMinutes(10),
                        Duration.ofDays(7),
                        null
                );

        assertThat(properties.sameSite())
                .isEqualTo("None");
    }

    @Test
    void constructorRejectsMissingSameSite() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        null,
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "same-site не задан"
                );
    }

    @Test
    void constructorRejectsInvalidSameSiteValue() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Invalid",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same-site");
    }

    @Test
    void constructorRejectsSameSiteNoneWithoutSecure() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "None",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Secure=true");
    }

    @Test
    void constructorRejectsNonPositiveAccessMaxAge() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ZERO,
                        Duration.ofDays(30),
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "access-token-max-age должен быть положительным"
                );
    }

    @Test
    void constructorRejectsNonPositiveRefreshMaxAge() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ZERO,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-max-age должен быть положительным"
                );
    }

    @Test
    void constructorRejectsAccessMaxAgeAboveMaximum() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(61),
                        Duration.ofDays(30),
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "access-token-max-age не должен превышать 60 минут"
                );
    }

    @Test
    void constructorRejectsRefreshMaxAgeLessThanAccessMaxAge() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(10),
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-max-age"
                );
    }

    @Test
    void constructorNormalizesCookieDomain() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        true,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        ".Example.COM"
                );

        assertThat(properties.domain())
                .isEqualTo("example.com");
        assertThat(properties.hasDomain()).isTrue();
    }

    @Test
    void constructorRejectsCookieDomainWithScheme() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        "https://example.com"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain-only");
    }

    @Test
    void constructorRejectsCookieDomainWithPort() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        "example.com:8080"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain-only");
    }

    @Test
    void constructorRejectsInvalidCookieDomain() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        "-example.com"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookies.domain");
    }
}