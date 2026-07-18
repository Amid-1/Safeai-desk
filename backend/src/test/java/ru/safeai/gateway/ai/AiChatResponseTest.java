package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatResponseTest {

    private static final Instant CALCULATED_AT =
            Instant.parse("2026-07-12T12:00:00Z");

    @Test
    void fromProviderPreservesPricedResponse() {
        AiChatResponse response = AiChatResponse.fromProvider(
                "Ответ",
                "gpt-4.1",
                "resp_123",
                AiResponseStatus.COMPLETED,
                "completed",
                100,
                50,
                new PricingResult(
                        PricingStatus.PRICED,
                        new BigDecimal("0.000600"),
                        "USD",
                        "openai-2026-07",
                        CALCULATED_AT
                )
        );

        assertThat(response.content())
                .isEqualTo("Ответ");

        assertThat(response.model())
                .isEqualTo("gpt-4.1");

        assertThat(response.providerMessageId())
                .isEqualTo("resp_123");

        assertThat(response.responseStatus())
                .isEqualTo(AiResponseStatus.COMPLETED);

        assertThat(response.finishReason())
                .isEqualTo("completed");

        assertThat(response.inputTokens())
                .isEqualTo(100);

        assertThat(response.outputTokens())
                .isEqualTo(50);

        assertThat(response.usageStatus())
                .isEqualTo(UsageStatus.AVAILABLE);

        assertThat(response.pricingStatus())
                .isEqualTo(PricingStatus.PRICED);

        assertThat(response.costUsd())
                .isEqualByComparingTo("0.000600");

        assertThat(response.currency())
                .isEqualTo("USD");

        assertThat(response.priceVersion())
                .isEqualTo("openai-2026-07");

        assertThat(response.pricingCalculatedAt())
                .isEqualTo(CALCULATED_AT);
    }

    @Test
    void determineUsageStatusDistinguishesAvailableMissingAndPartial() {
        assertThat(
                AiChatResponse.determineUsageStatus(0, 0)
        ).isEqualTo(UsageStatus.AVAILABLE);

        assertThat(
                AiChatResponse.determineUsageStatus(null, null)
        ).isEqualTo(UsageStatus.MISSING);

        assertThat(
                AiChatResponse.determineUsageStatus(10, null)
        ).isEqualTo(UsageStatus.PARTIAL);

        assertThat(
                AiChatResponse.determineUsageStatus(null, 20)
        ).isEqualTo(UsageStatus.PARTIAL);
    }

    @Test
    void constructorRejectsBlankContentAndModel() {
        assertThatThrownBy(() -> constructResponse(
                " ",
                "gpt-4.1",
                1,
                UsageStatus.AVAILABLE,
                PricingStatus.FREE,
                BigDecimal.ZERO,
                "USD",
                "v1"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");

        assertThatThrownBy(() -> constructResponse(
                "Ответ",
                " ",
                1,
                UsageStatus.AVAILABLE,
                PricingStatus.FREE,
                BigDecimal.ZERO,
                "USD",
                "v1"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void constructorRejectsNegativeTokens() {
        assertThatThrownBy(() -> constructResponse(
                "Ответ",
                "gpt-4.1",
                -1,
                UsageStatus.AVAILABLE,
                PricingStatus.PRICED,
                BigDecimal.ONE,
                "USD",
                "v1"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputTokens");
    }

    @Test
    void constructorRejectsUsageStatusInconsistentWithCounters() {
        assertThatThrownBy(() -> constructResponse(
                "Ответ",
                "gpt-4.1",
                null,
                UsageStatus.AVAILABLE,
                PricingStatus.UNPRICED,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE");

        assertThatThrownBy(() -> constructResponse(
                "Ответ",
                "gpt-4.1",
                1,
                UsageStatus.MISSING,
                PricingStatus.UNPRICED,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    void constructorRejectsInvalidPricingMetadata() {
        assertThatThrownBy(() -> constructResponse(
                "Ответ",
                "gpt-4.1",
                1,
                UsageStatus.AVAILABLE,
                PricingStatus.FREE,
                BigDecimal.ONE,
                "USD",
                "v1"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREE");

        assertThatThrownBy(() -> constructResponse(
                "Ответ",
                "gpt-4.1",
                1,
                UsageStatus.AVAILABLE,
                PricingStatus.UNPRICED,
                BigDecimal.ZERO,
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNPRICED");
    }

    private void constructResponse(
            String content,
            String model,
            Integer inputTokens,
            UsageStatus usageStatus,
            PricingStatus pricingStatus,
            BigDecimal costUsd,
            String currency,
            String priceVersion
    ) {
        new AiChatResponse(
                content,
                model,
                "provider-message-id",
                AiResponseStatus.COMPLETED,
                "completed",
                inputTokens,
                1,
                usageStatus,
                costUsd,
                pricingStatus,
                currency,
                priceVersion,
                CALCULATED_AT
        );
    }
}