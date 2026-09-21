package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.safeai.gateway.model.domain.ModelCapability;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRoutingExecutionCapabilityGateTest {
    @ParameterizedTest
    @MethodSource("absentSpecializedCapabilities")
    void plainTextRequestDoesNotRequireAnUnimplementedCapability(
            Set<ModelCapability> required
    ) {
        assertThat(ModelRoutingExecutionCapabilityGate.hasUnsupported(required)).isFalse();
    }

    private static Stream<Arguments> absentSpecializedCapabilities() {
        return Stream.of(
                Arguments.of(Set.of()),
                Arguments.of((Object) null)
        );
    }

    @ParameterizedTest
    @EnumSource(ModelCapability.class)
    void aCatalogDeclarationAloneCannotEnableUnimplementedProviderFeature(
            ModelCapability capability
    ) {
        assertThat(ModelRoutingExecutionCapabilityGate.hasUnsupported(Set.of(capability)))
                .as("Capability must be backed by data-plane, transport and accounting: %s", capability)
                .isTrue();
    }

    @Test
    void mixedRequiredCapabilitiesFailClosed() {
        assertThat(ModelRoutingExecutionCapabilityGate.hasUnsupported(
                Set.of(ModelCapability.TOOLS, ModelCapability.VISION)))
                .isTrue();
    }
}
