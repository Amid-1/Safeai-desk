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

    public ModelPricingProperties(List<ModelPrice> models) {
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

        modelsByNormalizedName = Map.copyOf(normalized);
    }

    /**
     * Только точное совпадение resolved model. Alias не подставляется
     * молча для snapshot-модели.
     */
    public ModelPrice find(String resolvedModel) {
        if (resolvedModel == null || resolvedModel.isBlank()) {
            return null;
        }

        return modelsByNormalizedName.get(
                resolvedModel.trim()
                        .toLowerCase(Locale.ROOT)
        );
    }

    /**
     * Flat ordinary input/output pricing snapshot.
     *
     * <p>Эта schema намеренно не утверждает поддержку cached input,
     * cache-write или tiered/long-context pricing. Если provider сообщает
     * фактически использованные specialized billing dimensions,
     * ModelPricingService fail-closed возвращает UNPRICED.</p>
     */
    public record ModelPrice(
            String model,
            BigDecimal inputUsdPer1mTokens,
            BigDecimal outputUsdPer1mTokens,
            String currency,
            String version
    ) {
        private static final int MAX_PRICE_SCALE = 12;
        private static final int MAX_PRICE_PRECISION = 24;

        public ModelPrice {
            if (model == null || model.isBlank()) {
                throw new IllegalStateException(
                        "pricing model не задан"
                );
            }

            model = model.trim();

            if (model.length() > 100) {
                throw new IllegalStateException(
                        "pricing model превышает 100 символов"
                );
            }

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

            if (!"USD".equals(currency)) {
                throw new IllegalStateException(
                        "Текущая схема costUsd поддерживает только USD"
                );
            }

            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        "pricing version не задана"
                );
            }

            version = version.trim();

            if (version.length() > 64) {
                throw new IllegalStateException(
                        "pricing version превышает 64 символа"
                );
            }
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
                        propertyName + " должен быть неотрицательным"
                );
            }
            if (value.scale() > MAX_PRICE_SCALE) {
                throw new IllegalStateException(
                        propertyName + " имеет чрезмерную точность"
                );
            }
            if (value.precision() > MAX_PRICE_PRECISION) {
                throw new IllegalStateException(
                        propertyName + " имеет чрезмерную precision"
                );
            }

            return value;
        }
    }
}
