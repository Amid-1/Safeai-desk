package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AiResponseMetadataServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-27T12:00:00Z");

    private final JsonMapper mapper =
            JsonMapper.builder().build();

    @Test
    void preservesSpecializedUsageAndFailsClosedToUnpriced() {
        AiResponseMetadataService service =
                service();

        JsonNode response =
                mapper.readTree("""
                        {
                          "usage":{
                            "input_tokens":20000,
                            "input_tokens_details":{
                              "cached_tokens":15000,
                              "cache_write_tokens":2000
                            },
                            "output_tokens":1000
                          }
                        }
                        """);

        AiResponseMetadataService.AiResponseMetadata metadata =
                service.extract(
                        response,
                        "gpt-snapshot"
                );

        assertThat(metadata.inputTokens())
                .isEqualTo(20_000);

        assertThat(metadata.cachedInputTokens())
                .isEqualTo(15_000);

        assertThat(metadata.cacheWriteInputTokens())
                .isEqualTo(2_000);

        assertThat(metadata.outputTokens())
                .isEqualTo(1_000);

        assertThat(
                metadata.specializedBillingDimensionsPresent()
        ).isTrue();

        assertThat(
                metadata.specializedBillingDimensionsValid()
        ).isTrue();

        assertThat(metadata.pricing().status())
                .isEqualTo(PricingStatus.UNPRICED);

        assertThat(metadata.pricing().costUsd())
                .isNull();
    }

    @Test
    void malformedSpecializedUsageIsCalculationFailedNotPriced() {
        AiResponseMetadataService service =
                service();

        JsonNode response =
                mapper.readTree("""
                        {
                          "usage":{
                            "input_tokens":100,
                            "input_tokens_details":{
                              "cached_tokens":"broken"
                            },
                            "output_tokens":10
                          }
                        }
                        """);

        AiResponseMetadataService.AiResponseMetadata metadata =
                service.extract(
                        response,
                        "gpt-snapshot"
                );

        assertThat(
                metadata.specializedBillingDimensionsPresent()
        ).isTrue();

        assertThat(
                metadata.specializedBillingDimensionsValid()
        ).isFalse();

        assertThat(metadata.pricing().status())
                .isEqualTo(
                        PricingStatus.CALCULATION_FAILED
                );

        assertThat(metadata.pricing().costUsd())
                .isNull();
    }

    private AiResponseMetadataService service() {
        ModelPricingProperties.ModelPrice price =
                new ModelPricingProperties.ModelPrice(
                        "gpt-snapshot",
                        new BigDecimal("2"),
                        new BigDecimal("8"),
                        "USD",
                        "v1"
                );

        ModelPricingService pricingService =
                new ModelPricingService(
                        new ModelPricingProperties(
                                List.of(price)
                        ),
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC
                        )
                );

        return new AiResponseMetadataService(
                pricingService
        );
    }
}
