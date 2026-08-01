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
                new ModelPricingProperties(List.of(price));

        assertThat(properties.find(" GPT-SNAPSHOT ")).isSameAs(price);
        assertThat(properties.find("gpt-alias")).isNull();
    }

    @Test
    void rejectsDuplicateNormalizedModels() {
        assertThatThrownBy(() -> new ModelPricingProperties(
                List.of(
                        price("gpt", "2", "8", "USD", "v1"),
                        price(" GPT ", "3", "9", "USD", "v2")
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Дублирующая");
    }

    @Test
    void rejectsNonUsdAndExcessivePriceScale() {
        assertThatThrownBy(() -> price(
                "gpt",
                "2",
                "8",
                "EUR",
                "v1"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USD");

        assertThatThrownBy(() -> price(
                "gpt",
                "0.0000000000001",
                "8",
                "USD",
                "v1"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("точность");
    }

    private ModelPricingProperties.ModelPrice price(
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
