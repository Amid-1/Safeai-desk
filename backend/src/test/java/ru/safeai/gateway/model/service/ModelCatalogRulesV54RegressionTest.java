package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression contract for:
 * <ul>
 *     <li>V49 independent cache pricing dimensions;</li>
 *     <li>V52 VISION / IMAGE capability-modality integrity;</li>
 *     <li>V59 pricing-version and numeric-envelope hardening.</li>
 * </ul>
 */
class ModelCatalogRulesV54RegressionTest {

    @Test
    void cacheWritePriceCanExceedOrdinaryInputPriceWithoutInvalidatingCatalog() {
        assertThatCode(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED,
                true,
                BigDecimal.ONE,
                new BigDecimal("0.2"),
                new BigDecimal("4.0"),
                new BigDecimal("8.0"),
                "pricing-v1"
        )).doesNotThrowAnyException();
    }

    @Test
    void cacheReadPriceCanExceedOrdinaryInputPriceWithoutInvalidatingCatalog() {
        assertThatCode(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED,
                true,
                BigDecimal.ONE,
                new BigDecimal("2.0"),
                null,
                new BigDecimal("8.0"),
                "pricing-v1"
        )).doesNotThrowAnyException();
    }

    @Test
    void visionRequiresImageInputModality() {
        assertThatThrownBy(() -> validate(
                Set.of(ModelCapability.VISION),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                null
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("VISION capability");
    }

    @Test
    void imageInputRequiresVisionCapability() {
        assertThatThrownBy(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT, ModelModality.IMAGE),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                null
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("VISION capability");
    }

    @Test
    void matchingVisionAndImageInputMetadataIsValidEvenIfRuntimeCannotExecuteVisionYet() {
        assertThatCode(() -> validate(
                Set.of(ModelCapability.VISION),
                Set.of(ModelModality.TEXT, ModelModality.IMAGE),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                null
        )).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ModelPricingStatus.class)
    void imageOutputIsNeverPersistableUnderCurrentCatalogSchema(ModelPricingStatus pricingStatus) {
        boolean completePricing = pricingStatus == ModelPricingStatus.FREE
                || pricingStatus == ModelPricingStatus.CONFIGURED;

        BigDecimal input = completePricing ? BigDecimal.ZERO : null;
        BigDecimal output = completePricing ? BigDecimal.ZERO : null;
        String pricingVersion = pricingStatus == ModelPricingStatus.CONFIGURED
                ? "v1"
                : null;

        assertThatThrownBy(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.IMAGE),
                pricingStatus,
                completePricing,
                input,
                null,
                null,
                output,
                pricingVersion
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("IMAGE output modality");
    }

    @Test
    void freeRejectsNonZeroCacheWriteEvenThoughConfiguredDoesNot() {
        assertThatThrownBy(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                null
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FREE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pricing-v1\nforged",
            "pricing-v1\rforged",
            "pricing-v1\tforged"
    })
    void pricingVersionRejectsControlCharactersAtApplicationBoundary(String pricingVersion) {
        assertThatThrownBy(() -> ModelCatalogRules.normalizePricingVersion(pricingVersion))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pricingVersion")
                .hasMessageContaining("control characters");
    }

    @Test
    void pricingVersionIsNormalizedBeforeCatalogSemanticValidation() {
        String normalizedPricingVersion =
                ModelCatalogRules.normalizePricingVersion("  pricing-v1  ");

        assertThat(normalizedPricingVersion)
                .isEqualTo("pricing-v1");

        assertThatCode(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED,
                true,
                BigDecimal.ONE,
                null,
                null,
                BigDecimal.ONE,
                normalizedPricingVersion
        )).doesNotThrowAnyException();
    }

    @Test
    void specializedInputRateParticipatesInWorstCaseNumericOverflowCheck() {
        BigDecimal hugeSpecializedInputRate =
                new BigDecimal("999999999999999999.000000000000");

        assertThatThrownBy(() -> ModelCatalogRules.validateCatalogSemantics(
                ModelLifecycle.ACTIVE,
                Integer.MAX_VALUE,
                1,
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.CONFIGURED,
                true,
                BigDecimal.ZERO,
                null,
                hugeSpecializedInputRate,
                BigDecimal.ZERO,
                "pricing-v1",
                "{}"
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("numeric(30,12)");
    }

    @Test
    void configuredPricingRequiresAnExactVersion() {
        assertThatThrownBy(() -> validate(
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelPricingStatus.CONFIGURED,
                true,
                BigDecimal.ONE,
                null,
                null,
                BigDecimal.ONE,
                null
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pricingVersion");
    }

    private static void validate(
            Set<ModelCapability> capabilities,
            Set<ModelModality> inputModalities,
            Set<ModelModality> outputModalities,
            ModelPricingStatus pricingStatus,
            boolean pricingComplete,
            BigDecimal input,
            BigDecimal cachedInput,
            BigDecimal cacheWrite,
            BigDecimal output,
            String pricingVersion
    ) {
        ModelCatalogRules.validateCatalogSemantics(
                ModelLifecycle.ACTIVE,
                32_000,
                4_096,
                capabilities,
                inputModalities,
                outputModalities,
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                pricingStatus,
                pricingComplete,
                input,
                cachedInput,
                cacheWrite,
                output,
                pricingVersion,
                "{}"
        );
    }
}
