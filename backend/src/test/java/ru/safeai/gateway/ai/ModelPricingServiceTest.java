package ru.safeai.gateway.ai;

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

class ModelPricingServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-12T12:00:00Z");

    @Test
    void calculatesVersionedPrice() {
        ModelPricingService service = service(List.of(
                price("gpt-4.1", "2.00", "8.00", "openai-2026-07")
        ));

        PricingResult result = service.calculate(
                "gpt-4.1",
                1_000_000,
                500_000,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd()).isEqualByComparingTo("6.000000");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.priceVersion()).isEqualTo("openai-2026-07");
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    void identifiesConfiguredFreeModel() {
        PricingResult result = service(List.of(
                price("mock-safeai", "0", "0", "mock-v1")
        )).calculate(
                "mock-safeai",
                10,
                20,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.FREE);
        assertThat(result.costUsd()).isEqualByComparingTo("0.000000");
    }

    @Test
    void unknownModelIsUnpricedNotFree() {
        PricingResult result = service(List.of()).calculate(
                "unknown-model",
                10,
                20,
                UsageStatus.AVAILABLE
        );

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    @Test
    void missingOrPartialUsageIsUnpriced() {
        ModelPricingService service = service(List.of(
                price("gpt-4.1", "2", "8", "v1")
        ));

        assertThat(service.calculate(
                "gpt-4.1",
                null,
                null,
                UsageStatus.MISSING
        ).status()).isEqualTo(PricingStatus.UNPRICED);

        assertThat(service.calculate(
                "gpt-4.1",
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
