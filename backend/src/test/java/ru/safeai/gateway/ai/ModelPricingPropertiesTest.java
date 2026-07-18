package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPricingPropertiesTest {

    @Test
    void findsModelCaseInsensitively() {
        ModelPricingProperties.ModelPrice price = price(
                "gpt-4.1",
                "2.00",
                "8.00",
                "USD",
                "openai-2026-07"
        );

        ModelPricingProperties properties =
                new ModelPricingProperties(List.of(price));

        assertThat(properties.find(" GPT-4.1 "))
                .isSameAs(price);
    }

    @Test
    void rejectsDuplicateNormalizedModels() {
        assertThatThrownBy(() -> new ModelPricingProperties(
                List.of(
                        price("gpt-4.1", "2", "8", "USD", "v1"),
                        price(" GPT-4.1 ", "3", "9", "USD", "v2")
                )
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Дублирующая");
    }

    @Test
    void rejectsBlankModelNegativePriceInvalidCurrencyAndBlankVersion() {
        assertThatThrownBy(() ->
                price(" ", "2", "8", "USD", "v1")
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                price("gpt", "-1", "8", "USD", "v1")
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                price("gpt", "1", "8", "US", "v1")
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                price("gpt", "1", "8", "USD", " ")
        ).isInstanceOf(IllegalStateException.class);
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
