package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract for V49 independent cache rates and V52 capability/modality gate. */
class ModelCatalogRulesV54RegressionTest {

    @Test
    void cacheWritePriceCanExceedOrdinaryInputPriceWithoutInvalidatingCatalog() {
        assertThatCode(() -> validate(
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED, true,
                BigDecimal.ONE, new BigDecimal("0.2"),
                new BigDecimal("4.0"), new BigDecimal("8.0"), "pricing-v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void cacheReadPriceCanExceedOrdinaryInputPriceWithoutInvalidatingCatalog() {
        assertThatCode(() -> validate(
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED, true,
                BigDecimal.ONE, new BigDecimal("2.0"),
                null, new BigDecimal("8.0"), "pricing-v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void visionRequiresImageInputModality() {
        assertThatThrownBy(() -> validate(
                Set.of(ModelCapability.VISION), Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT), ModelPricingStatus.FREE, true,
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("VISION capability");
    }

    @Test
    void imageInputRequiresVisionCapability() {
        assertThatThrownBy(() -> validate(
                Set.of(), Set.of(ModelModality.TEXT, ModelModality.IMAGE),
                Set.of(ModelModality.TEXT), ModelPricingStatus.FREE, true,
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("VISION capability");
    }

    @Test
    void matchingVisionAndImageInputMetadataIsValidEvenIfRuntimeCannotExecuteVisionYet() {
        assertThatCode(() -> validate(
                Set.of(ModelCapability.VISION), Set.of(ModelModality.TEXT, ModelModality.IMAGE),
                Set.of(ModelModality.TEXT), ModelPricingStatus.FREE, true,
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ModelPricingStatus.class)
    void imageOutputIsNeverPersistableUnderCurrentCatalogSchema(ModelPricingStatus pricing) {
        boolean complete = pricing == ModelPricingStatus.FREE
                || pricing == ModelPricingStatus.CONFIGURED;
        BigDecimal input = complete ? BigDecimal.ZERO : null;
        BigDecimal output = complete ? BigDecimal.ZERO : null;
        String version = pricing == ModelPricingStatus.CONFIGURED ? "v1" : null;
        assertThatThrownBy(() -> validate(
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.IMAGE),
                pricing, complete, input, null, null, output, version))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("IMAGE output modality");
    }

    @Test
    void freeRejectsNonZeroCacheWriteEvenThoughConfiguredDoesNot() {
        assertThatThrownBy(() -> validate(
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelPricingStatus.FREE, true,
                BigDecimal.ZERO, null, BigDecimal.ONE, BigDecimal.ZERO, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FREE");
    }

    @Test
    void configuredPricingRequiresAnExactVersion() {
        assertThatThrownBy(() -> validate(
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED, true,
                BigDecimal.ONE, null, null, BigDecimal.ONE, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pricingVersion");
    }

    private static void validate(
            Set<ModelCapability> capabilities,
            Set<ModelModality> input,
            Set<ModelModality> output,
            ModelPricingStatus status,
            boolean complete,
            BigDecimal ordinaryInput,
            BigDecimal cacheRead,
            BigDecimal cacheWrite,
            BigDecimal ordinaryOutput,
            String version
    ) {
        ModelCatalogRules.validateCatalogSemantics(
                ModelLifecycle.ACTIVE, 32_000, 4_096, capabilities, input, output,
                ModelRetentionStatus.NOT_DECLARED, null,
                ModelTrainingUseStatus.NOT_DECLARED, status, complete,
                ordinaryInput, cacheRead, cacheWrite, ordinaryOutput, version, "{}");
    }
}
