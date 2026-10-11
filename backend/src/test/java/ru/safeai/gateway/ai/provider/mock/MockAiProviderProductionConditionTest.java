
package ru.safeai.gateway.ai.provider.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MockAiProviderProductionConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            ModelPricingService.class,
                            () -> mock(ModelPricingService.class)
                    )
                    .withBean(
                            AiProviderRetryExecutor.class,
                            () -> mock(AiProviderRetryExecutor.class)
                    )
                    .withUserConfiguration(MockAiProvider.class);

    @Test
    void canonicalProductionProfilesDoNotRegisterMockProvider() {
        for (String profile : new String[]{"prod", "production"}) {
            contextRunner
                    .withInitializer(context ->
                            context.getEnvironment()
                                    .setActiveProfiles(profile)
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context)
                                .doesNotHaveBean(MockAiProvider.class);
                    });
        }
    }

    @Test
    void qualifiedProductionProfilesDoNotRegisterMockProvider() {
        for (String profile : new String[]{
                "prod-k8s",
                "production-eu"
        }) {
            contextRunner
                    .withInitializer(context ->
                            context.getEnvironment()
                                    .setActiveProfiles(profile)
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context)
                                .doesNotHaveBean(MockAiProvider.class);
                    });
        }
    }

    @Test
    void explicitProductionMarkerDoesNotRegisterMockProvider() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment()
                                .setActiveProfiles("aws-live")
                )
                .withPropertyValues(
                        "safeai.environment=production"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(MockAiProvider.class);
                });
    }

    @Test
    void localProfileRegistersMockProviderByDefault() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment()
                                .setActiveProfiles("local")
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(MockAiProvider.class);
                });
    }
}
