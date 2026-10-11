
package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionEnvironmentConditionTest {

    @Test
    void qualifiedProductionProfileIsRecognized() {
        assertThat(matches(null, "production-eu")).isTrue();
    }

    @Test
    void explicitProductionMarkerWorksWithoutCanonicalProfile() {
        assertThat(matches("production", "aws-live")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "prod",
            "production",
            "prod-k8s",
            "production-eu",
            "PRODUCTION_US",
            "prod.eu"
    })
    void canonicalAndQualifiedProductionProfilesAreRecognized(
            String profile
    ) {
        assertThat(matches(null, profile)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "production",
            "PROD",
            " production ",
            "prod-k8s"
    })
    void explicitProductionNamesAreRecognized(String marker) {
        assertThat(matches(marker, "local")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "development",
            "staging",
            "preproduction",
            "productionish",
            "prod-",
            "production_"
    })
    void nonProductionNamesAreNotRecognized(String marker) {
        assertThat(
                ProductionEnvironmentCondition.isProductionName(marker)
        ).isFalse();
    }

    @Test
    void absentMarkerAndUnrelatedProfilesAreNotProduction() {
        assertThat(matches(null, "dev", "aws-live")).isFalse();
    }

    @Test
    void productionProfileStillEnablesProtectionWithUnrelatedExplicitMarker() {
        assertThat(matches("staging", "production-eu")).isTrue();
    }

    @Test
    void noActiveProfilesDoesNotAccidentallyEnableProduction() {
        assertThat(matches(null)).isFalse();
    }

    @Test
    void missingContextEnvironmentIsNotSilentlyTreatedAsNonProduction() {
        ConditionContext context = mock(ConditionContext.class);

        assertThatNullPointerException()
                .isThrownBy(() ->
                        new ProductionEnvironmentCondition().matches(
                                context,
                                mock(AnnotatedTypeMetadata.class)
                        )
                )
                .withMessageContaining("context.environment");
    }

    /**
     * Regression test for the shared public production detector.
     */
    @Test
    void publicDetectorRecognizesProductionMarkerAndQualifiedProfiles() {
        MockEnvironment explicitEnvironment = new MockEnvironment();

        explicitEnvironment.setActiveProfiles("aws-live");
        explicitEnvironment.setProperty(
                "safeai.environment",
                "production"
        );

        assertThat(
                ProductionEnvironmentCondition.isProduction(
                        explicitEnvironment
                )
        ).isTrue();

        MockEnvironment qualifiedProfile = new MockEnvironment();

        qualifiedProfile.setActiveProfiles("production-eu");

        assertThat(
                ProductionEnvironmentCondition.isProduction(
                        qualifiedProfile
                )
        ).isTrue();

        MockEnvironment conflictingSignals = new MockEnvironment();

        conflictingSignals.setActiveProfiles("prod-k8s");
        conflictingSignals.setProperty(
                "safeai.environment",
                "staging"
        );

        assertThat(
                ProductionEnvironmentCondition.isProduction(
                        conflictingSignals
                )
        ).isTrue();
    }

    /**
     * Local and test environments must retain non-production behavior.
     */
    @Test
    void publicDetectorDoesNotClassifyLocalOrTestAsProduction() {
        MockEnvironment local = new MockEnvironment();

        local.setActiveProfiles("local");

        assertThat(
                ProductionEnvironmentCondition.isProduction(local)
        ).isFalse();

        MockEnvironment test = new MockEnvironment();

        test.setActiveProfiles("test");
        test.setProperty(
                "safeai.environment",
                "development"
        );

        assertThat(
                ProductionEnvironmentCondition.isProduction(test)
        ).isFalse();
    }

    private boolean matches(
            String explicitMarker,
            String... profiles
    ) {
        ConditionContext context = mock(ConditionContext.class);
        Environment environment = mock(Environment.class);

        when(context.getEnvironment()).thenReturn(environment);

        when(environment.getProperty("safeai.environment"))
                .thenReturn(explicitMarker);

        // Active profiles are not read when the explicit marker
        // already identifies a production environment.
        org.mockito.Mockito.lenient()
                .when(environment.getActiveProfiles())
                .thenReturn(profiles);

        return new ProductionEnvironmentCondition().matches(
                context,
                mock(AnnotatedTypeMetadata.class)
        );
    }
}
