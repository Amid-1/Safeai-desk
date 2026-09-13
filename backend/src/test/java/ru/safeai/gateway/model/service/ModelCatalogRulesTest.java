package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogRulesTest {

    @Test
    void modelAndProviderIdentityAreCanonicalizedDeterministically() {
        assertThat(ModelCatalogRules.normalizeModelKey(
                " OpenAI:GPT-Test "
        )).isEqualTo("openai:gpt-test");

        assertThat(ModelCatalogRules.normalizeProvider(
                " OpenAI "
        )).isEqualTo("openai");
    }

    @Test
    void invalidModelKeyIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() ->
                ModelCatalogRules.normalizeModelKey(
                        "gpt test"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("недопустимые символы");
    }

    @Test
    void emptyPricingJsonIsCanonicalizedToObject() {
        assertThat(ModelCatalogRules.validateExtraPricingJson(
                " {   } "
        )).isEqualTo("{}");
    }

    @Test
    void nonObjectPricingJsonIsRejected() {
        assertThatThrownBy(() ->
                ModelCatalogRules.validateExtraPricingJson(
                        "[]"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void freePricingRequiresZeroOrdinaryAndSpecializedPrices() {
        assertThatThrownBy(() ->
                validateCompletePricing(
                        ModelPricingStatus.FREE,
                        BigDecimal.ZERO,
                        new BigDecimal("0.01"),
                        null,
                        BigDecimal.ZERO,
                        null
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FREE");
    }

    @Test
    void configuredPricingRejectsSpecializedPriceAboveInput() {
        assertThatThrownBy(() ->
                validateCompletePricing(
                        ModelPricingStatus.CONFIGURED,
                        new BigDecimal("1"),
                        null,
                        new BigDecimal("1.01"),
                        new BigDecimal("4"),
                        "pricing-v1"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cacheWriteInputUsdPer1mTokens");
    }

    @Test
    void imageOutputCannotBePersistedByV45Schema() {
        assertThatThrownBy(() ->
                ModelCatalogRules.validateCatalogSemantics(
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        Set.of(ModelModality.TEXT),
                        Set.of(ModelModality.IMAGE),
                        ModelRetentionStatus.NOT_DECLARED,
                        null,
                        ModelTrainingUseStatus.NOT_DECLARED,
                        ModelPricingStatus.FREE,
                        true,
                        BigDecimal.ZERO,
                        null,
                        null,
                        BigDecimal.ZERO,
                        null,
                        "{}"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("IMAGE output modality");
    }

    private static void validateCompletePricing(
            ModelPricingStatus pricingStatus,
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
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                pricingStatus,
                true,
                input,
                cachedInput,
                cacheWrite,
                output,
                pricingVersion,
                "{}"
        );
    }
}
