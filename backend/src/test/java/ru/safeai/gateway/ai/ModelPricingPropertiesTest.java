package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ModelPricingPropertiesTest {

    @Test
    void exactModelLookupIsCaseInsensitiveButDoesNotUseAliases() {
        ModelPricingProperties.ModelPrice price = price(
                "gpt-snapshot",
                "2",
                "8",
                "USD",
                "v1"
        );

        ModelPricingProperties properties =
                new ModelPricingProperties(
                        List.of(price)
                );

        assertThat(
                properties.find(
                        " GPT-SNAPSHOT "
                )
        ).isSameAs(price);

        assertThat(
                properties.find(
                        "gpt-alias"
                )
        ).isNull();
    }

    @Test
    void blankOrNullLookupDoesNotResolveModel() {
        ModelPricingProperties properties =
                new ModelPricingProperties(
                        List.of(
                                price(
                                        "gpt-snapshot",
                                        "2",
                                        "8",
                                        "USD",
                                        "v1"
                                )
                        )
                );

        assertThat(properties.find(null))
                .isNull();
        assertThat(properties.find("   "))
                .isNull();
    }

    @Test
    void rejectsDuplicateNormalizedModels() {
        assertThatThrownBy(() ->
                new ModelPricingProperties(
                        List.of(
                                price(
                                        "gpt",
                                        "2",
                                        "8",
                                        "USD",
                                        "v1"
                                ),
                                price(
                                        " GPT ",
                                        "3",
                                        "9",
                                        "USD",
                                        "v2"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "Дублирующая pricing model"
                );
    }

    @Test
    void rejectsNonUsdCurrency() {
        assertThatThrownBy(() ->
                price(
                        "gpt",
                        "2",
                        "8",
                        "EUR",
                        "v1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "только USD"
                );
    }

    @Test
    void rejectsPriceScaleGreaterThanPostgresNumeric30Scale12() {
        assertThatThrownBy(() ->
                price(
                        "gpt",
                        "0.0000000000001",
                        "8",
                        "USD",
                        "v1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "input-usd-per-1m-tokens"
                )
                .hasMessageContaining(
                        "не более 12 знаков после запятой"
                );
    }

    @Test
    void rejectsPriceWithMoreThanEighteenIntegerDigits() {
        assertThatThrownBy(() ->
                price(
                        "gpt",
                        "1000000000000000000",
                        "8",
                        "USD",
                        "v1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "input-usd-per-1m-tokens"
                )
                .hasMessageContaining(
                        "PostgreSQL numeric(30,12)"
                );
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() ->
                price(
                        "gpt",
                        "-0.01",
                        "8",
                        "USD",
                        "v1"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "input-usd-per-1m-tokens"
                )
                .hasMessageContaining(
                        "неотрицательным"
                );
    }

    @Test
    void zeroInputAndOutputPricesAreRecognizedAsFree() {
        ModelPricingProperties.ModelPrice price =
                price(
                        "free-model",
                        "0",
                        "0.000000000000",
                        "USD",
                        "v1"
                );

        assertThat(price.free())
                .isTrue();
    }

    @Test
    void onlyBothZeroPricesAreRecognizedAsFree() {
        ModelPricingProperties.ModelPrice price =
                price(
                        "paid-model",
                        "0",
                        "0.01",
                        "USD",
                        "v1"
                );

        assertThat(price.free())
                .isFalse();
    }

    @Test
    void modelCurrencyAndVersionAreCanonicalized() {
        ModelPricingProperties.ModelPrice price =
                price(
                        "  GPT-SNAPSHOT  ",
                        "2",
                        "8",
                        " usd ",
                        "  pricing-v1  "
                );

        assertThat(price.model())
                .isEqualTo(
                        "GPT-SNAPSHOT"
                );
        assertThat(price.normalizedModel())
                .isEqualTo(
                        "gpt-snapshot"
                );
        assertThat(price.currency())
                .isEqualTo(
                        "USD"
                );
        assertThat(price.version())
                .isEqualTo(
                        "pricing-v1"
                );
    }

    @Test
    void nullConfigurationProducesEmptyPricingSnapshot() {
        ModelPricingProperties properties =
                new ModelPricingProperties(
                        null
                );

        assertThat(properties.models())
                .isEmpty();
    }

    private static ModelPricingProperties.ModelPrice price(
            String model,
            String input,
            String output,
            String currency,
            String version
    ) {
        return new ModelPricingProperties.ModelPrice(
                model,
                new BigDecimal(input),
                new BigDecimal(output),
                currency,
                version
        );
    }
}
