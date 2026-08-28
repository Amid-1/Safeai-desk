package ru.safeai.gateway.ai.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
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

    private static final int INTERMEDIATE_SCALE = 18;
    private static final int RESULT_SCALE = 12;

    private final ModelPricingProperties properties;
    private final Clock clock;

    public PricingResult calculate(
            String resolvedModel,
            Integer inputTokens,
            Integer outputTokens,
            UsageStatus usageStatus
    ) {
        return calculate(
                resolvedModel,
                AiTokenUsage.basic(
                        inputTokens,
                        outputTokens
                ),
                usageStatus
        );
    }

    public PricingResult calculate(
            String resolvedModel,
            AiTokenUsage usage
    ) {
        if (usage == null) {
            return PricingResult.calculationFailed(
                    clock.instant()
            );
        }

        return calculate(
                resolvedModel,
                usage,
                usage.usageStatus()
        );
    }

    private PricingResult calculate(
            String resolvedModel,
            AiTokenUsage usage,
            UsageStatus usageStatus
    ) {
        Instant calculatedAt =
                clock.instant();

        if (usageStatus != UsageStatus.AVAILABLE) {
            return PricingResult.unpriced(
                    calculatedAt
            );
        }

        if (!validTokenCounters(
                usage.inputTokens(),
                usage.outputTokens()
        )) {
            return PricingResult.calculationFailed(
                    calculatedAt
            );
        }

        if (usage.specializedBillingDimensionsPresent()) {
            if (!usage.specializedBillingDimensionsValid()
                    || !validSpecializedTokenCounters(
                            usage.cachedInputTokens(),
                            usage.cacheWriteInputTokens()
                    )) {
                return PricingResult.calculationFailed(
                        calculatedAt
                );
            }

            /*
             * Financial correctness boundary.
             *
             * Текущая ModelPrice описывает только ordinary input/output.
             * Если provider фактически использовал cached/cache-write
             * billing dimensions, нельзя выдавать приблизительную цену
             * как PRICED. Нулевые specialized counters безопасны, потому
             * что не изменяют фактическую стоимость запроса.
             */
            if (usage.hasSpecializedBillableTokens()) {
                return PricingResult.unpriced(
                        calculatedAt
                );
            }
        }

        ModelPricingProperties.ModelPrice price =
                properties.find(
                        resolvedModel
                );

        if (price == null) {
            return PricingResult.unpriced(
                    calculatedAt
            );
        }

        try {
            BigDecimal total =
                    calculateTotalCost(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            price
                    );

            PricingStatus pricingStatus =
                    price.free()
                            ? PricingStatus.FREE
                            : PricingStatus.PRICED;

            if (pricingStatus
                    == PricingStatus.PRICED
                    && total.signum() == 0) {

                return PricingResult.calculationFailed(
                        calculatedAt
                );
            }

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

    private boolean validSpecializedTokenCounters(
            Integer cachedInputTokens,
            Integer cacheWriteInputTokens
    ) {
        return (cachedInputTokens == null
                || cachedInputTokens >= 0)
                && (cacheWriteInputTokens == null
                || cacheWriteInputTokens >= 0);
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
