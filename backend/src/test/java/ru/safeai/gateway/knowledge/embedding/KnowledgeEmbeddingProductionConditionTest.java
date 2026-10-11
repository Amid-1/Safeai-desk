
package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEmbeddingProductionConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            KnowledgeEmbeddingProperties.class,
                            () -> new KnowledgeEmbeddingProperties(
                                    "hashing",
                                    null,
                                    null,
                                    null,
                                    384,
                                    null,
                                    null,
                                    null,
                                    null
                            )
                    )
                    .withUserConfiguration(
                            KnowledgeEmbeddingProductionInvariantVerifier.class
                    );

    @Test
    void canonicalProductionProfilesRejectHashing() {
        for (String profile : new String[]{"prod", "production"}) {
            contextRunner
                    .withInitializer(context ->
                            context.getEnvironment()
                                    .setActiveProfiles(profile)
                    )
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalStateException.class)
                                .hasStackTraceContaining("hashing");
                    });
        }
    }

    @Test
    void qualifiedProductionProfilesRejectHashing() {
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
                                .hasRootCauseInstanceOf(IllegalStateException.class)
                                .hasStackTraceContaining("hashing");
                    });
        }
    }

    @Test
    void explicitProductionMarkerRejectsHashing() {
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
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("hashing");
                });
    }

    @Test
    void localProfileAllowsHashing() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment()
                                .setActiveProfiles("local")
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            KnowledgeEmbeddingProductionInvariantVerifier.class
                    );
                });
    }
}
