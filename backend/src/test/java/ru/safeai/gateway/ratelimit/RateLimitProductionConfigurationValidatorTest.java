package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitProductionConfigurationValidatorTest {

    private static final UUID AUDIT_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    @Test
    void productionAcceptsEnabledLoginRefreshAndAiLimiters() {
        RateLimitProductionConfigurationValidator validator =
                validator(
                        "prod",
                        true,
                        true,
                        true,
                        false
                );

        assertThatCode(
                validator::validate
        ).doesNotThrowAnyException();
    }

    @Test
    void productionRejectsDisabledLoginLimiter() {
        RateLimitProductionConfigurationValidator validator =
                validator(
                        "prod",
                        false,
                        true,
                        true,
                        false
                );

        assertThatThrownBy(
                validator::validate
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.rate-limit.login.enabled"
                );
    }

    @Test
    void productionRejectsDisabledRefreshLimiter() {
        RateLimitProductionConfigurationValidator validator =
                validator(
                        "production",
                        true,
                        false,
                        true,
                        false
                );

        assertThatThrownBy(
                validator::validate
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.rate-limit.refresh.enabled"
                );
    }

    @Test
    void productionRejectsDisabledAiLimiterWithoutExplicitEscapeHatch() {
        RateLimitProductionConfigurationValidator validator =
                validator(
                        "prod",
                        true,
                        true,
                        false,
                        false
                );

        assertThatThrownBy(
                validator::validate
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "allow-unlimited-ai-traffic"
                );
    }

    @Test
    void productionAllowsDisabledAiLimiterWithExplicitEscapeHatch() {
        RateLimitProductionConfigurationValidator validator =
                validator(
                        "prod",
                        true,
                        true,
                        false,
                        true
                );

        assertThatCode(
                validator::validate
        ).doesNotThrowAnyException();
    }

    @Test
    void localProfileMayDisableLimiters() {
        RateLimitProductionConfigurationValidator validator =
                validator(
                        "local",
                        false,
                        false,
                        false,
                        false
                );

        assertThatCode(
                validator::validate
        ).doesNotThrowAnyException();
    }

    private RateLimitProductionConfigurationValidator validator(
            String profile,
            boolean loginEnabled,
            boolean refreshEnabled,
            boolean aiEnabled,
            boolean allowUnlimitedAiTraffic
    ) {
        MockEnvironment environment =
                new MockEnvironment();

        environment.setActiveProfiles(
                profile
        );

        return new RateLimitProductionConfigurationValidator(
                new LoginRateLimitProperties(
                        loginEnabled,
                        10,
                        30,
                        Duration.ofMinutes(10),
                        loginEnabled
                                ? AUDIT_ORGANIZATION_ID
                                : null
                ),
                new RefreshRateLimitProperties(
                        refreshEnabled,
                        600,
                        Duration.ofMinutes(1)
                ),
                new AiMessageRateLimitProperties(
                        aiEnabled,
                        20,
                        100,
                        1_000
                ),
                new RateLimitProductionProperties(
                        allowUnlimitedAiTraffic
                ),
                environment
        );
    }
}
