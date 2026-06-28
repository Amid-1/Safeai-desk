package ru.safeai.gateway.ai.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class ModelPricingService {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final ModelPricingProperties properties;

    public BigDecimal calculateCostUsd(
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {
        ModelPricingProperties.ModelPrice price = findPrice(model);

        if (price == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal inputCost = BigDecimal
                .valueOf(safeTokens(inputTokens))
                .multiply(price.safeInputUsdPer1mTokens())
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);

        BigDecimal outputCost = BigDecimal
                .valueOf(safeTokens(outputTokens))
                .multiply(price.safeOutputUsdPer1mTokens())
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);

        return inputCost
                .add(outputCost)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private ModelPricingProperties.ModelPrice findPrice(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        String normalizedModel = model.trim().toLowerCase();

        return properties.effectiveModels()
                .stream()
                .filter(price -> price.model() != null)
                .filter(price -> normalizedModel.equals(price.model().trim().toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    private long safeTokens(Integer value) {
        return value == null || value < 0 ? 0L : value;
    }
}
