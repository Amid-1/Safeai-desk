package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ModelPricingServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-12T12:00:00Z");

    @Test
    void calculatesVersionedPriceAtScaleTwelve() {
        PricingResult result = service(List.of(
                price("gpt-snapshot", "2.00", "8.00", "v1")
        )).calculate(
                "gpt-snapshot",
                1_000_000,
                500_000,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd())
                .isEqualByComparingTo("6.000000000000");
        assertThat(result.costUsd().scale()).isEqualTo(12);
    }

    @Test
    void verySmallNonZeroCostIsNotLostAtScaleSix() {
        PricingResult result = service(List.of(
                price("tiny", "0.20", "0", "tiny-v1")
        )).calculate(
                "tiny",
                1,
                0,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd())
                .isEqualByComparingTo("0.000000200000");
        assertThat(result.costUsd()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void actualModelWithoutExactRuleIsUnpriced() {
        PricingResult result = service(List.of(
                price("gpt-alias", "2", "8", "alias-v1")
        )).calculate(
                "gpt-snapshot",
                10,
                20,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    @Test
    void configuredZeroPriceIsFree() {
        PricingResult result = service(List.of(
                price("mock-safeai", "0", "0", "mock-v1")
        )).calculate(
                "mock-safeai",
                10,
                20,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.FREE);
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void missingOrPartialUsageIsUnpriced() {
        ModelPricingService service = service(List.of(
                price("gpt", "2", "8", "v1")
        ));

        assertThat(service.calculate(
                "gpt",
                null,
                null,
                UsageStatus.MISSING
        ).status()).isEqualTo(PricingStatus.UNPRICED);

        assertThat(service.calculate(
                "gpt",
                10,
                null,
                UsageStatus.PARTIAL
        ).status()).isEqualTo(PricingStatus.UNPRICED);
    }

    private ModelPricingService service(
            List<ModelPricingProperties.ModelPrice> prices
    ) {
        return new ModelPricingService(
                new ModelPricingProperties(prices),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ModelPricingProperties.ModelPrice price(
            String model,
            String input,
            String output,
            String version
    ) {
        return new ModelPricingProperties.ModelPrice(
                model,
                new BigDecimal(input),
                new BigDecimal(output),
                "USD",
                version
        );
    }
}
