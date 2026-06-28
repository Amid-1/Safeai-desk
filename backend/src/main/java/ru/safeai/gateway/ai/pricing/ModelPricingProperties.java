package ru.safeai.gateway.ai.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "safeai.ai.pricing")
public record ModelPricingProperties(
        List<ModelPrice> models
) {

    public List<ModelPrice> effectiveModels() {
        return models == null ? List.of() : List.copyOf(models);
    }

    public record ModelPrice(
            String model,
            BigDecimal inputUsdPer1mTokens,
            BigDecimal outputUsdPer1mTokens
    ) {
        public BigDecimal safeInputUsdPer1mTokens() {
            return inputUsdPer1mTokens == null || inputUsdPer1mTokens.signum() < 0
                    ? BigDecimal.ZERO
                    : inputUsdPer1mTokens;
        }

        public BigDecimal safeOutputUsdPer1mTokens() {
            return outputUsdPer1mTokens == null || outputUsdPer1mTokens.signum() < 0
                    ? BigDecimal.ZERO
                    : outputUsdPer1mTokens;
        }
    }
}
