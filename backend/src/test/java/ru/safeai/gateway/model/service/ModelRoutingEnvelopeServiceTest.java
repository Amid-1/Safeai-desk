package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.config.ModelRoutingEnvelopeProperties;
import ru.safeai.gateway.model.domain.ModelCapability;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRoutingEnvelopeServiceTest {
    @Test
    void reservesRagAndToolSchemasAdditivelyInInputUnits() {
        var service = new ModelRoutingEnvelopeService(
                new ModelRoutingEnvelopeProperties(100L, 200L, 300L, null, null, null));
        assertThat(service.additionalInputUnitUpperBound(false, Set.of())).isEqualTo(100L);
        assertThat(service.additionalInputUnitUpperBound(true, Set.of())).isEqualTo(300L);
        assertThat(service.additionalInputUnitUpperBound(false, Set.of(ModelCapability.TOOLS)))
                .isEqualTo(400L);
        assertThat(service.additionalInputUnitUpperBound(true, Set.of(ModelCapability.TOOLS)))
                .isEqualTo(600L);
    }

    @Test
    void doesNotReserveToolSchemaJustForVisionOrStructuredOutput() {
        var service = new ModelRoutingEnvelopeService(
                new ModelRoutingEnvelopeProperties(100L, 200L, 300L, null, null, null));
        assertThat(service.additionalInputUnitUpperBound(false,
                Set.of(ModelCapability.VISION, ModelCapability.STRUCTURED_OUTPUT)))
                .isEqualTo(100L);
        assertThat(service.additionalInputUnitUpperBound(true, null)).isEqualTo(300L);
    }

    @Test
    void legacyAndPreferredAliasesMustAgree() {
        assertThatThrownBy(() -> new ModelRoutingEnvelopeProperties(
                100L, null, null, 101L, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy");
        var same = new ModelRoutingEnvelopeProperties(
                100L, 200L, 300L, 100L, 200L, 300L);
        assertThat(same.systemAndDeveloperTokens()).isEqualTo(100L);
        assertThat(same.ragContextInputUnits()).isEqualTo(200L);
    }

    @Test
    void envelopeComponentAboveHardBoundFailsAtConfigurationTime() {
        assertThatThrownBy(() -> new ModelRoutingEnvelopeProperties(
                1_000_001L, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1\u00a0000\u00a0000".replace("\u00a0", ""));
    }
}
