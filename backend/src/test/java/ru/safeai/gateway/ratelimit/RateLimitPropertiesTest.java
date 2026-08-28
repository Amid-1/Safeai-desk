package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final String VALID_SECRET =
            "0123456789abcdef0123456789abcdef";

    @Test
    void validPropertiesKeepConfiguredValues() {
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
                        Duration.ofMinutes(10),
                        PLATFORM_ORGANIZATION_ID
                );

        assertThat(ai.isEnabled()).isTrue();
        assertThat(ai.effectiveUserLimitPerHour())
                .isEqualTo(20);
        assertThat(ai.effectiveAdminLimitPerHour())
                .isEqualTo(100);
        assertThat(ai.effectiveOrganizationLimitPerHour())
                .isEqualTo(1_000);

        assertThat(login.isEnabled()).isTrue();
        assertThat(login.effectiveEmailLimit())
                .isEqualTo(10);
        assertThat(login.effectiveIpLimit())
                .isEqualTo(30);
        assertThat(login.effectiveWindow())
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(login.effectiveAuditOrganizationId())
                .isEqualTo(PLATFORM_ORGANIZATION_ID);
    }

    @Test
    void nullEnabledDefaultsToEnabled() {
        AiMessageRateLimitProperties ai =
                new AiMessageRateLimitProperties(
                        null,
                        20,
                        100,
                        1_000
                );

        LoginRateLimitProperties login =
                new LoginRateLimitProperties(
                        null,
                        10,
                        30,
                        Duration.ofMinutes(10),
                        PLATFORM_ORGANIZATION_ID
                );

        assertThat(ai.isEnabled()).isTrue();
        assertThat(login.isEnabled()).isTrue();
    }

    @Test
    void aiLimitBoundariesAreValidated() {
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
    void loginLimitAndWindowBoundariesAreValidated() {
        assertThatThrownBy(() ->
                new LoginRateLimitProperties(
                        true,
                        0,
                        30,
                        Duration.ofMinutes(10),
                        PLATFORM_ORGANIZATION_ID
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new LoginRateLimitProperties(
                        true,
                        10,
                        30,
                        Duration.ofMillis(999),
                        PLATFORM_ORGANIZATION_ID
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new LoginRateLimitProperties(
                        true,
                        10,
                        30,
                        Duration.ofHours(24)
                                .plusMillis(1),
                        PLATFORM_ORGANIZATION_ID
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(
                new LoginRateLimitProperties(
                        true,
                        1,
                        1,
                        Duration.ofSeconds(1),
                        PLATFORM_ORGANIZATION_ID
                ).effectiveWindow()
        ).isEqualTo(Duration.ofSeconds(1));

        assertThat(
                new LoginRateLimitProperties(
                        true,
                        10_000,
                        10_000,
                        Duration.ofHours(24),
                        PLATFORM_ORGANIZATION_ID
                ).effectiveWindow()
        ).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void enabledLoginRequiresAuditOrganization() {
        assertThatThrownBy(() ->
                new LoginRateLimitProperties(
                        true,
                        10,
                        30,
                        Duration.ofMinutes(10),
                        null
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "audit-organization-id"
                );

        assertThatCodeForDisabledLoginWithNullAuditOrganization();
    }

    private void assertThatCodeForDisabledLoginWithNullAuditOrganization() {
        LoginRateLimitProperties disabled =
                new LoginRateLimitProperties(
                        false,
                        10,
                        30,
                        Duration.ofMinutes(10),
                        null
                );

        assertThat(disabled.isEnabled()).isFalse();
    }

    @Test
    void productionUnlimitedAiEscapeHatchDefaultsToFalse() {
        RateLimitProductionProperties defaults =
                new RateLimitProductionProperties(
                        null
                );

        RateLimitProductionProperties explicit =
                new RateLimitProductionProperties(
                        true
                );

        assertThat(
                defaults.isUnlimitedAiTrafficAllowed()
        ).isFalse();

        assertThat(
                explicit.isUnlimitedAiTrafficAllowed()
        ).isTrue();
    }

    @Test
    void redisKeyPropertiesNormalizePrefixAndDefaultVersion() {
        RateLimitRedisKeyProperties properties =
                new RateLimitRedisKeyProperties(
                        " safeai:prod::: ",
                        VALID_SECRET,
                        null
                );

        assertThat(properties.keyPrefix())
                .isEqualTo("safeai:prod");
        assertThat(properties.hmacSecret())
                .isEqualTo(VALID_SECRET);
        assertThat(properties.keyVersion())
                .isEqualTo("v1");
    }

    @Test
    void redisKeyPropertiesRejectInvalidValues() {
        assertThatThrownBy(() ->
                new RateLimitRedisKeyProperties(
                        " ",
                        VALID_SECRET,
                        "v1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining("key-prefix");

        assertThatThrownBy(() ->
                new RateLimitRedisKeyProperties(
                        "safeai:{prod}",
                        VALID_SECRET,
                        "v1"
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new RateLimitRedisKeyProperties(
                        "safeai:prod",
                        "too-short",
                        "v1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining("hmac-secret");

        assertThatThrownBy(() ->
                new RateLimitRedisKeyProperties(
                        "safeai:prod",
                        VALID_SECRET,
                        "v 1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining("key-version");
    }
}
