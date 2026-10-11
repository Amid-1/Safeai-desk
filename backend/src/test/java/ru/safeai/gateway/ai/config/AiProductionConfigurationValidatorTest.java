package ru.safeai.gateway.ai.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.safeai.gateway.ai.provider.AiProviderProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AiProductionConfigurationValidatorTest {
    @Test
    void prodAndProductionRejectMockIncludingDefault() {
        for (String profile : new String[]{"prod", "production"}) {
            for (String provider : new String[]{"mock", null}) {
                assertThatThrownBy(() -> validator(profile, provider).validate())
                        .as("%s / %s", profile, provider)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("safeai.ai.provider")
                        .hasMessageContaining("openai или anthropic");
            }
        }
    }

    @Test
    void productionAcceptsOnlyApprovedProviderImplementations() {
        for (String profile : new String[]{"prod", "production"}) {
            for (String provider : new String[]{"openai", "anthropic", " OpenAI "}) {
                assertThatCode(() -> validator(profile, provider).validate())
                        .as("%s / %s", profile, provider).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void localProfileMayUseMock() {
        assertThatCode(() -> validator("local", "mock").validate())
                .doesNotThrowAnyException();
    }


    @Test
    void qualifiedProductionProfilesRejectMockProvider() {
        for (String profile : new String[]{
                "prod-k8s",
                "production-eu"
        }) {
            assertThatThrownBy(() ->
                    validator(profile, "mock").validate()
            )
                    .as("profile=%s", profile)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("safeai.ai.provider");
        }
    }

    @Test
    void explicitProductionMarkerRejectsMockProvider() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("aws-live");
        environment.setProperty(
                "safeai.environment",
                "production"
        );

        AiProductionConfigurationValidator validator =
                new AiProductionConfigurationValidator(
                        new AiProviderProperties("mock"),
                        environment
                );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safeai.ai.provider");
    }


    private static AiProductionConfigurationValidator validator(String activeProfile, String provider) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfile);
        return new AiProductionConfigurationValidator(new AiProviderProperties(provider), environment);
    }
}
