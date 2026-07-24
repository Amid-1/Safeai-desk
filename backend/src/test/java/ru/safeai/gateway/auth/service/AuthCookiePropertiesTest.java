package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookiePropertiesTest {

    private static final Duration ACCESS_MAX_AGE =
            Duration.ofMinutes(15);

    private static final Duration REFRESH_MAX_AGE =
            Duration.ofDays(30);

    private static final Duration REFRESH_ABSOLUTE_MAX_AGE =
            Duration.ofDays(60);

    private static final Duration REUSE_DETECTION_RETENTION =
            Duration.ofDays(7);

    @Test
    void constructorPreservesValidValuesAndAppliesDefaultNames() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                );

        assertThat(properties.secure())
                .isFalse();

        assertThat(properties.sameSite())
                .isEqualTo("Lax");

        assertThat(properties.accessTokenMaxAge())
                .isEqualTo(ACCESS_MAX_AGE);

        assertThat(properties.refreshTokenMaxAge())
                .isEqualTo(REFRESH_MAX_AGE);

        assertThat(properties.refreshTokenAbsoluteMaxAge())
                .isEqualTo(REFRESH_ABSOLUTE_MAX_AGE);

        assertThat(properties.reuseDetectionRetention())
                .isEqualTo(REUSE_DETECTION_RETENTION);

        assertThat(properties.domain())
                .isNull();

        assertThat(properties.hasDomain())
                .isFalse();

        assertThat(properties.accessTokenName())
                .isEqualTo("safeai-access");

        assertThat(properties.refreshTokenName())
                .isEqualTo("safeai-refresh");
    }

    @Test
    void constructorNormalizesSameSiteAndDomain() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        true,
                        "none",
                        Duration.ofMinutes(10),
                        Duration.ofDays(7),
                        Duration.ofDays(30),
                        Duration.ofDays(7),
                        ".Example.COM",
                        null,
                        null
                );

        assertThat(properties.sameSite())
                .isEqualTo("None");

        assertThat(properties.domain())
                .isEqualTo("example.com");

        assertThat(properties.hasDomain())
                .isTrue();

        /*
         * __Host- нельзя использовать при наличии Domain.
         */
        assertThat(properties.accessTokenName())
                .isEqualTo("safeai-access");

        assertThat(properties.refreshTokenName())
                .isEqualTo("__Secure-safeai-refresh");
    }

    @Test
    void secureHostOnlyConfigurationUsesHardenedCookieNames() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        true,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                );

        assertThat(properties.accessTokenName())
                .isEqualTo("__Host-safeai-access");

        assertThat(properties.refreshTokenName())
                .isEqualTo("__Secure-safeai-refresh");
    }

    @Test
    void constructorPreservesValidCustomCookieNames() {
        AuthCookieProperties properties =
                new AuthCookieProperties(
                        false,
                        "Strict",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        "custom-access",
                        "custom-refresh"
                );

        assertThat(properties.accessTokenName())
                .isEqualTo("custom-access");

        assertThat(properties.refreshTokenName())
                .isEqualTo("custom-refresh");
    }

    @Test
    void constructorRejectsMissingSameSite() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        null,
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "same-site не задан"
                );
    }

    @Test
    void constructorRejectsInvalidSameSite() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Invalid",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same-site");
    }

    @Test
    void sameSiteNoneRequiresSecure() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "None",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
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
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
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
                        ACCESS_MAX_AGE,
                        Duration.ZERO,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-max-age должен быть положительным"
                );
    }

    @Test
    void constructorRejectsNonPositiveAbsoluteMaxAge() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        Duration.ZERO,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-absolute-max-age "
                                + "должен быть положительным"
                );
    }

    @Test
    void constructorRejectsNonPositiveReuseRetention() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        Duration.ZERO,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "reuse-detection-retention "
                                + "должен быть положительным"
                );
    }

    @Test
    void constructorRejectsAccessMaxAgeAboveMaximum() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(61),
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "access-token-max-age не должен превышать 60 минут"
                );
    }

    @Test
    void constructorRejectsRefreshMaxAgeAboveMaximum() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        Duration.ofDays(91),
                        Duration.ofDays(91),
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-max-age не должен превышать 90 дней"
                );
    }

    @Test
    void constructorRejectsAbsoluteMaxAgeAboveMaximum() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        Duration.ofDays(91),
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-absolute-max-age "
                                + "не должен превышать 90 дней"
                );
    }

    @Test
    void constructorRejectsReuseRetentionAboveMaximum() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        Duration.ofDays(31),
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "reuse-detection-retention "
                                + "не должен превышать 30 дней"
                );
    }

    @Test
    void refreshMaxAgeMustExceedAccessMaxAge() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(10),
                        Duration.ofDays(30),
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-max-age должен быть больше "
                                + "access-token-max-age"
                );
    }

    @Test
    void absoluteMaxAgeMustNotBeLessThanRefreshMaxAge() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        Duration.ofDays(30),
                        Duration.ofDays(20),
                        REUSE_DETECTION_RETENTION,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "refresh-token-absolute-max-age "
                                + "не должен быть меньше "
                                + "refresh-token-max-age"
                );
    }

    @Test
    void domainMustNotContainScheme() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        "https://example.com",
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "без scheme, path, port и IP-адреса"
                );
    }

    @Test
    void domainMustNotContainPort() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        "example.com:8080",
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "без scheme, path, port и IP-адреса"
                );
    }

    @Test
    void domainMustNotBeIpAddress() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        "127.0.0.1",
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "без scheme, path, port и IP-адреса"
                );
    }

    @Test
    void constructorRejectsInvalidCookieName() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        "invalid cookie name",
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Некорректное имя cookie"
                );
    }

    @Test
    void securePrefixRequiresSecureFlag() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        false,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        "__Secure-custom-access",
                        "custom-refresh"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "__Secure- требует Secure=true"
                );
    }

    @Test
    void hostPrefixForAccessCookieRejectsConfiguredDomain() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        "example.com",
                        "__Host-custom-access",
                        "__Secure-custom-refresh"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "__Host- требует Secure=true, "
                                + "отсутствие Domain и Path=/"
                );
    }

    @Test
    void hostPrefixCannotBeUsedForRefreshCookiePath() {
        assertThatThrownBy(() ->
                new AuthCookieProperties(
                        true,
                        "Lax",
                        ACCESS_MAX_AGE,
                        REFRESH_MAX_AGE,
                        REFRESH_ABSOLUTE_MAX_AGE,
                        REUSE_DETECTION_RETENTION,
                        null,
                        "__Host-custom-access",
                        "__Host-custom-refresh"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "__Host- требует Secure=true, "
                                + "отсутствие Domain и Path=/"
                );
    }
}