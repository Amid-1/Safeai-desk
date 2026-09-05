package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
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

class ModelCatalogRulesTest {

    @Test
    void modelAndProviderIdentityAreCanonicalizedDeterministically() {
        assertThat(
                ModelCatalogRules.normalizeModelKey(
                        " OpenAI:GPT-Test "
                )
        ).isEqualTo(
                "openai:gpt-test"
        );

        assertThat(
                ModelCatalogRules.normalizeProvider(
                        " OpenAI "
                )
        ).isEqualTo(
                "openai"
        );
    }

    @Test
    void invalidModelKeyIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() ->
                ModelCatalogRules.normalizeModelKey(
                        "gpt test"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(
                        "недопустимые символы"
                );
    }

    @Test
    void emptyPricingJsonIsCanonicalizedToObject() {
        assertThat(
                ModelCatalogRules.validateExtraPricingJson(
                        " {   } "
                )
        ).isEqualTo(
                "{}"
        );
    }

    @Test
    void nonObjectPricingJsonIsRejected() {
        assertThatThrownBy(() ->
                ModelCatalogRules.validateExtraPricingJson(
                        "[]"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(
                        "JSON object"
                );
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
                .hasMessageContaining(
                        "FREE"
                );
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
                .hasMessageContaining(
                        "cacheWriteInputUsdPer1mTokens"
                );
    }

    /**
     * V48 keeps IMAGE output fail-closed.
     *
     * <p>The current provider/catalog data plane supports TEXT output only.
     * IMAGE output must therefore be rejected independently of input
     * capability semantics.</p>
     */
    @Test
    void imageOutputCannotBePersistedByCurrentSchema() {
        assertThatThrownBy(() ->
                ModelCatalogRules.validateCatalogSemantics(
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        Set.of(),
                        Set.of(
                                ModelModality.TEXT
                        ),
                        Set.of(
                                ModelModality.IMAGE
                        ),
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
                .hasMessageContaining(
                        "IMAGE output modality"
                );
    }

    /**
     * V48 semantic invariant:
     *
     * <p>A catalog entry must not claim VISION capability while declaring only
     * TEXT input. Otherwise routing could believe that IMAGE input is supported
     * although the modality contract says it is not.</p>
     */
    @Test
    void visionCapabilityRequiresImageInputModality() {
        assertThatThrownBy(() ->
                ModelCatalogRules.validateCatalogSemantics(
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        Set.of(
                                ModelCapability.VISION
                        ),
                        Set.of(
                                ModelModality.TEXT
                        ),
                        Set.of(
                                ModelModality.TEXT
                        ),
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
                .hasMessageContaining(
                        "VISION capability"
                )
                .hasMessageContaining(
                        "IMAGE input modality"
                );
    }

    /**
     * Reverse side of the same V48 invariant.
     *
     * <p>IMAGE input must not be silently persisted without VISION capability,
     * because model selection operates on capabilities as well as modalities.</p>
     */
    @Test
    void imageInputModalityRequiresVisionCapability() {
        assertThatThrownBy(() ->
                ModelCatalogRules.validateCatalogSemantics(
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        Set.of(),
                        Set.of(
                                ModelModality.TEXT,
                                ModelModality.IMAGE
                        ),
                        Set.of(
                                ModelModality.TEXT
                        ),
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
                .hasMessageContaining(
                        "VISION capability"
                )
                .hasMessageContaining(
                        "IMAGE input modality"
                );
    }

    /**
     * Catalog metadata is internally valid when VISION and IMAGE input are
     * declared together.
     *
     * <p>This does not by itself enable multimodal execution. The separate
     * execution capability gate remains responsible for keeping unsupported
     * provider data-plane functionality fail-closed.</p>
     */
    @Test
    void visionCapabilityAndImageInputModalityCanBeDeclaredTogether() {
        assertThatCode(() ->
                ModelCatalogRules.validateCatalogSemantics(
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        Set.of(
                                ModelCapability.VISION
                        ),
                        Set.of(
                                ModelModality.TEXT,
                                ModelModality.IMAGE
                        ),
                        Set.of(
                                ModelModality.TEXT
                        ),
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
        ).doesNotThrowAnyException();
    }

    /**
     * Existing TEXT-only catalog semantics remain valid after the V48
     * capability/modality contract was introduced.
     */
    @Test
    void textOnlyModelDoesNotRequireCapabilities() {
        assertThatCode(() ->
                ModelCatalogRules.validateCatalogSemantics(
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        Set.of(),
                        Set.of(
                                ModelModality.TEXT
                        ),
                        Set.of(
                                ModelModality.TEXT
                        ),
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
        ).doesNotThrowAnyException();
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

                /*
                 * Pricing tests exercise pricing semantics only.
                 * TEXT-only models require no special capabilities.
                 */
                Set.of(),

                Set.of(
                        ModelModality.TEXT
                ),
                Set.of(
                        ModelModality.TEXT
                ),
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