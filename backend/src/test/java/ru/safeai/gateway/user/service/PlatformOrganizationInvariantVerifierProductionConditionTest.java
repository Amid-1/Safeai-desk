
package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlatformOrganizationInvariantVerifierProductionConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            PlatformProperties.class,
                            () -> mock(PlatformProperties.class)
                    )
                    .withBean(
                            OrganizationRepository.class,
                            () -> mock(OrganizationRepository.class)
                    )
                    .withBean(
                            UserRepository.class,
                            () -> mock(UserRepository.class)
                    )
                    .withUserConfiguration(
                            PlatformOrganizationInvariantVerifier.class
                    );

    @Test
    void canonicalProductionProfilesRegisterVerifier() {
        for (String profile : new String[]{"prod", "production"}) {
            contextRunner
                    .withInitializer(context ->
                            context.getEnvironment()
                                    .setActiveProfiles(profile)
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(
                                PlatformOrganizationInvariantVerifier.class
                        );
                    });
        }
    }

    @Test
    void qualifiedProductionProfilesRegisterVerifier() {
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
                        assertThat(context).hasSingleBean(
                                PlatformOrganizationInvariantVerifier.class
                        );
                    });
        }
    }

    @Test
    void explicitProductionMarkerRegistersVerifier() {
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
                    assertThat(context).hasSingleBean(
                            PlatformOrganizationInvariantVerifier.class
                    );
                });
    }

    @Test
    void localProfileDoesNotRegisterVerifier() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment()
                                .setActiveProfiles("local")
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            PlatformOrganizationInvariantVerifier.class
                    );
                });
    }
}
