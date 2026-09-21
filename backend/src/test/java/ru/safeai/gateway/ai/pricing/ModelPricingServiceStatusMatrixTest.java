package ru.safeai.gateway.ai.pricing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Complements ModelPricingServiceTest. This is the legacy static price resolver,
 * not the V54 catalog-backed authoritative execution settlement path. */
@Tag("unit")
class ModelPricingServiceStatusMatrixTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private final ModelPricingService service = new ModelPricingService(
            new ModelPricingProperties(List.of(
                    price("paid", "2", "8", "static-paid-v1"),
                    price("free", "0", "0", "static-free-v1"),
                    price("output-only", "0", "8", "static-partial-v1"),
                    price("tiny", "0.000000000001", "0", "tiny-v1"))),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void paidExactlyKnownAndAvailableUsageProducesVersionedPositiveUsd() {
        PricingResult result = service.calculate("paid", 1_000, 500, UsageStatus.AVAILABLE);
        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd()).isEqualByComparingTo("0.006000000000");
        assertThat(result.costUsd().scale()).isEqualTo(12);
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.priceVersion()).isEqualTo("static-paid-v1");
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    void configuredFreePriceProducesFreeNotUnpriced() {
        PricingResult result = service.calculate("free", 100, 20, UsageStatus.AVAILABLE);
        assertThat(result.status()).isEqualTo(PricingStatus.FREE);
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.costUsd().scale()).isEqualTo(12);
        assertThat(result.priceVersion()).isEqualTo("static-free-v1");
    }

    @Test
    void nonFreeTariffWithZeroBillableUsageMustNotBeMarkedFree() {
        PricingResult result = service.calculate("paid", 0, 0, UsageStatus.AVAILABLE);
        assertUnknown(result, PricingStatus.CALCULATION_FAILED);
        // Existing pricing contract requires PRICED > 0 and FREE iff configured rates are both zero.
    }

    @Test
    void roundToZeroUnderNumericScaleIsNotClaimedAsPriced() {
        PricingResult result = service.calculate("tiny", 1, 0, UsageStatus.AVAILABLE);
        assertUnknown(result, PricingStatus.CALCULATION_FAILED);
    }

    @Test
    void freeStatusDependsOnBothConfiguredRatesNotJustOneDimensionOfActualUsage() {
        PricingResult result = service.calculate("output-only", 100, 0, UsageStatus.AVAILABLE);
        assertUnknown(result, PricingStatus.CALCULATION_FAILED);
    }

    @Test
    void unknownModelAndIncompleteUsageCannotClaimKnownMoney() {
        assertUnknown(service.calculate("unconfigured", 1, 2, UsageStatus.AVAILABLE),
                PricingStatus.UNPRICED);
        assertUnknown(service.calculate("paid", null, null, UsageStatus.MISSING),
                PricingStatus.UNPRICED);
        assertUnknown(service.calculate("paid", 1, null, UsageStatus.PARTIAL),
                PricingStatus.UNPRICED);
    }

    @Test
    void malformedAvailableUsageAndNullUsageAreCalculationFailed() {
        assertUnknown(service.calculate("paid", null, 10, UsageStatus.AVAILABLE),
                PricingStatus.CALCULATION_FAILED);
        assertUnknown(service.calculate("paid", -1, 10, UsageStatus.AVAILABLE),
                PricingStatus.CALCULATION_FAILED);
        assertUnknown(service.calculate("paid", null),
                PricingStatus.CALCULATION_FAILED);
    }

    @Test
    void cacheReadOrWriteWithoutSpecializedRateIsUnpriced() {
        assertUnknown(service.calculate("paid", new AiTokenUsage(
                20_000, 15_000, null, 500, true, true)), PricingStatus.UNPRICED);
        assertUnknown(service.calculate("paid", new AiTokenUsage(
                20_000, null, 2_000, 500, true, true)), PricingStatus.UNPRICED);
    }

    @Test
    void malformedSpecializedUsageIsCalculationFailedEvenIfFlatTariffExists() {
        assertUnknown(service.calculate("paid", new AiTokenUsage(
                20, null, null, 3, true, false)), PricingStatus.CALCULATION_FAILED);
        assertUnknown(service.calculate("paid", new AiTokenUsage(
                20, -1, null, 3, true, true)), PricingStatus.CALCULATION_FAILED);
    }

    private static ModelPricingProperties.ModelPrice price(String model,
                                                             String input,
                                                             String output,
                                                             String version) {
        return new ModelPricingProperties.ModelPrice(model,
                new BigDecimal(input), new BigDecimal(output), "USD", version);
    }

    private static void assertUnknown(PricingResult result, PricingStatus expected) {
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.costUsd()).isNull();
        assertThat(result.currency()).isNull();
        assertThat(result.priceVersion()).isNull();
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }
}
