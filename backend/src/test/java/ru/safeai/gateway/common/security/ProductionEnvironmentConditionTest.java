package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

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
    @ValueSource(strings = {"prod", "production", "prod-k8s", "production-eu", "PRODUCTION_US", "prod.eu"})
    void canonicalAndQualifiedProductionProfilesAreRecognized(String profile) {
        assertThat(matches(null, profile)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "PROD", " production ", "prod-k8s"})
    void explicitProductionNamesAreRecognized(String marker) {
        assertThat(matches(marker, "local")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "development", "staging", "preproduction", "productionish", "prod-", "production_"})
    void nonProductionNamesAreNotRecognized(String marker) {
        assertThat(ProductionEnvironmentCondition.isProductionName(marker)).isFalse();
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
        assertThatNullPointerException().isThrownBy(() ->
                new ProductionEnvironmentCondition().matches(
                        context, mock(AnnotatedTypeMetadata.class)
                )).withMessageContaining("context.environment");
    }

    private boolean matches(String explicitMarker, String... profiles) {
        ConditionContext context = mock(ConditionContext.class);
        Environment environment = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("safeai.environment")).thenReturn(explicitMarker);
        // This method is not invoked when an explicit marker already matches.
        org.mockito.Mockito.lenient().when(environment.getActiveProfiles()).thenReturn(profiles);
        return new ProductionEnvironmentCondition().matches(
                context, mock(AnnotatedTypeMetadata.class)
        );
    }
}
