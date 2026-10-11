
package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeStorageProductionConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            KnowledgeStorageProperties.class,
                            () -> new KnowledgeStorageProperties(
                                    KnowledgeStorageType.LOCAL,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null
                            )
                    )
                    .withUserConfiguration(
                            KnowledgeStorageProductionInvariantVerifier.class
                    );

    @Test
    void canonicalProductionProfilesRejectLocalStorage() {
        for (String profile : new String[]{
                "prod",
                "production"
        }) {
            contextRunner
                    .withInitializer(context ->
                            context.getEnvironment()
                                    .setActiveProfiles(profile)
                    )
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(
                                        IllegalStateException.class
                                )
                                .hasStackTraceContaining("LOCAL storage");
                    });
        }
    }

    @Test
    void qualifiedProductionProfilesRejectLocalStorage() {
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
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(
                                        IllegalStateException.class
                                )
                                .hasStackTraceContaining("LOCAL storage");
                    });
        }
    }

    @Test
    void explicitProductionMarkerRejectsLocalStorage() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment()
                                .setActiveProfiles("aws-live")
                )
                .withPropertyValues(
                        "safeai.environment=production"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalStateException.class
                            )
                            .hasStackTraceContaining("LOCAL storage");
                });
    }

    @Test
    void localProfileAllowsLocalStorage() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment()
                                .setActiveProfiles("local")
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            KnowledgeStorageProductionInvariantVerifier.class
                    );
                });
    }
}
