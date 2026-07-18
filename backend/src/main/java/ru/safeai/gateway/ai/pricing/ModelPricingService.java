package ru.safeai.gateway.ai.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ModelPricingService {

    private static final BigDecimal ONE_MILLION =
            BigDecimal.valueOf(1_000_000L);

    private static final int INTERMEDIATE_SCALE = 12;
    private static final int RESULT_SCALE = 6;

    private final ModelPricingProperties properties;
    private final Clock clock;

    public PricingResult calculate(
            String model,
            Integer inputTokens,
            Integer outputTokens,
            UsageStatus usageStatus
    ) {
        Instant calculatedAt = clock.instant();

        if (usageStatus != UsageStatus.AVAILABLE) {
            return PricingResult.unpriced(
                    calculatedAt
            );
        }

        if (!validTokenCounters(
                inputTokens,
                outputTokens
        )) {
            return PricingResult.calculationFailed(
                    calculatedAt
            );
        }

        ModelPricingProperties.ModelPrice price =
                properties.find(model);

        if (price == null) {
            return PricingResult.unpriced(
                    calculatedAt
            );
        }

        try {
            BigDecimal total = calculateTotalCost(
                    inputTokens,
                    outputTokens,
                    price
            );

            PricingStatus pricingStatus = price.free()
                    ? PricingStatus.FREE
                    : PricingStatus.PRICED;

            return new PricingResult(
                    pricingStatus,
                    total,
                    price.currency(),
                    price.version(),
                    calculatedAt
            );
        } catch (ArithmeticException exception) {
            return PricingResult.calculationFailed(
                    calculatedAt
            );
        }
    }

    private boolean validTokenCounters(
            Integer inputTokens,
            Integer outputTokens
    ) {
        return inputTokens != null
                && outputTokens != null
                && inputTokens >= 0
                && outputTokens >= 0;
    }

    private BigDecimal calculateTotalCost(
            int inputTokens,
            int outputTokens,
            ModelPricingProperties.ModelPrice price
    ) {
        BigDecimal inputCost = calculateTokenCost(
                inputTokens,
                price.inputUsdPer1mTokens()
        );

        BigDecimal outputCost = calculateTokenCost(
                outputTokens,
                price.outputUsdPer1mTokens()
        );

        return inputCost
                .add(outputCost)
                .setScale(
                        RESULT_SCALE,
                        RoundingMode.HALF_UP
                );
    }

    private BigDecimal calculateTokenCost(
            int tokens,
            BigDecimal pricePerMillionTokens
    ) {
        return BigDecimal
                .valueOf(tokens)
                .multiply(pricePerMillionTokens)
                .divide(
                        ONE_MILLION,
                        INTERMEDIATE_SCALE,
                        RoundingMode.HALF_UP
                );
    }
}