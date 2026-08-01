package ru.safeai.gateway.ai.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.safeai.gateway.ai.provider.AiProviderProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AiProductionConfigurationValidatorTest {

    private static final String PRODUCTION_PROFILE =
            "prod";

    private static final String LOCAL_PROFILE =
            "local";

    @Test
    void productionRejectsMockProvider() {
        AiProductionConfigurationValidator validator =
                validator(
                        PRODUCTION_PROFILE,
                        "mock"
                );

        assertThatThrownBy(
                validator::validate
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "safeai.ai.provider"
                )
                .hasMessageContaining(
                        "openai или anthropic"
                )
                .hasMessageContaining(
                        "mock"
                );
    }

    @Test
    void productionAcceptsOpenAiProvider() {
        AiProductionConfigurationValidator validator =
                validator(
                        PRODUCTION_PROFILE,
                        "openai"
                );

        assertThatCode(
                validator::validate
        ).doesNotThrowAnyException();
    }

    @Test
    void productionAcceptsAnthropicProvider() {
        AiProductionConfigurationValidator validator =
                validator(
                        PRODUCTION_PROFILE,
                        "anthropic"
                );

        assertThatCode(
                validator::validate
        ).doesNotThrowAnyException();
    }

    @Test
    void localProfileMayUseMockProvider() {
        AiProductionConfigurationValidator validator =
                validator(
                        LOCAL_PROFILE,
                        "mock"
                );

        assertThatCode(
                validator::validate
        ).doesNotThrowAnyException();
    }

    private static AiProductionConfigurationValidator validator(
            String activeProfile,
            String provider
    ) {
        MockEnvironment environment =
                new MockEnvironment();

        environment.setActiveProfiles(
                activeProfile
        );

        return new AiProductionConfigurationValidator(
                new AiProviderProperties(
                        provider
                ),
                environment
        );
    }
}