package ru.safeai.gateway.ai.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ConfigurationProperties(prefix = "safeai.ai.pricing")
public final class ModelPricingProperties {

    private final Map<String, ModelPrice> modelsByNormalizedName;

    public ModelPricingProperties(
            List<ModelPrice> models
    ) {
        List<ModelPrice> safeModels = models == null
                ? List.of()
                : List.copyOf(models);

        Map<String, ModelPrice> normalized =
                new LinkedHashMap<>();

        for (ModelPrice price : safeModels) {
            if (price == null) {
                throw new IllegalStateException(
                        "pricing models не должен содержать null"
                );
            }

            String key = price.normalizedModel();

            if (normalized.putIfAbsent(key, price) != null) {
                throw new IllegalStateException(
                        "Дублирующая pricing model: "
                                + price.model()
                );
            }
        }

        modelsByNormalizedName =
                Map.copyOf(normalized);
    }

    public ModelPrice find(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        return modelsByNormalizedName.get(
                model.trim()
                        .toLowerCase(Locale.ROOT)
        );
    }

    public record ModelPrice(
            String model,
            BigDecimal inputUsdPer1mTokens,
            BigDecimal outputUsdPer1mTokens,
            String currency,
            String version
    ) {

        private static final int MAX_PRICE_SCALE = 12;

        public ModelPrice {
            if (model == null || model.isBlank()) {
                throw new IllegalStateException(
                        "pricing model не задан"
                );
            }

            model = model.trim();

            inputUsdPer1mTokens = validatePrice(
                    inputUsdPer1mTokens,
                    "input-usd-per-1m-tokens"
            );

            outputUsdPer1mTokens = validatePrice(
                    outputUsdPer1mTokens,
                    "output-usd-per-1m-tokens"
            );

            if (currency == null || currency.isBlank()) {
                throw new IllegalStateException(
                        "pricing currency не задана"
                );
            }

            currency = currency
                    .trim()
                    .toUpperCase(Locale.ROOT);

            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalStateException(
                        "pricing currency должен быть "
                                + "трёхбуквенным ISO-4217 кодом"
                );
            }

            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        "pricing version не задана"
                );
            }

            version = version.trim();
        }

        public String normalizedModel() {
            return model.toLowerCase(Locale.ROOT);
        }

        public boolean free() {
            return inputUsdPer1mTokens.signum() == 0
                    && outputUsdPer1mTokens.signum() == 0;
        }

        private static BigDecimal validatePrice(
                BigDecimal value,
                String propertyName
        ) {
            if (value == null) {
                throw new IllegalStateException(
                        propertyName + " не задан"
                );
            }

            if (value.signum() < 0) {
                throw new IllegalStateException(
                        propertyName
                                + " должен быть неотрицательным"
                );
            }

            if (value.scale() > MAX_PRICE_SCALE) {
                throw new IllegalStateException(
                        propertyName
                                + " имеет чрезмерную точность"
                );
            }

            return value;
        }
    }
}