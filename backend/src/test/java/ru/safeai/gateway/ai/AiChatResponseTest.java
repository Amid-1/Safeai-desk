package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
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

@Tag("unit")
class AiChatResponseTest {

    private static final Instant CALCULATED_AT =
            Instant.parse("2026-07-12T12:00:00Z");

    private static final String CONTENT =
            "Ответ";

    private static final String REQUESTED_MODEL =
            "gpt-4.1";

    private static final String RESOLVED_MODEL =
            "gpt-4.1-2026-07-15";

    private static final String PROVIDER_MESSAGE_ID =
            "resp_123";

    private static final String PROVIDER_REQUEST_ID =
            "request_123";

    private static final String FINISH_REASON =
            "completed";

    private static final String CURRENCY_USD =
            "USD";

    private static final String PRICE_VERSION =
            "openai-2026-07";

    private static final BigDecimal PRICED_COST =
            new BigDecimal("0.000600000000");

    private static final BigDecimal POSITIVE_COST =
            new BigDecimal("1.000000000000");

    private static final BigDecimal ZERO_COST =
            new BigDecimal("0.000000000000");

    @Test
    void fromProviderPreservesRequestedResolvedAndProviderIds() {
        PricingResult pricing =
                new PricingResult(
                        PricingStatus.PRICED,
                        PRICED_COST,
                        CURRENCY_USD,
                        PRICE_VERSION,
                        CALCULATED_AT
                );

        AiChatResponse response =
                AiChatResponse.fromProvider(
                        CONTENT,
                        REQUESTED_MODEL,
                        RESOLVED_MODEL,
                        PROVIDER_MESSAGE_ID,
                        PROVIDER_REQUEST_ID,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        100,
                        50,
                        pricing
                );

        assertThat(response.content())
                .isEqualTo(CONTENT);

        assertThat(response.requestedModel())
                .isEqualTo(REQUESTED_MODEL);

        assertThat(response.model())
                .isEqualTo(RESOLVED_MODEL);

        assertThat(response.providerMessageId())
                .isEqualTo(PROVIDER_MESSAGE_ID);

        assertThat(response.providerRequestId())
                .isEqualTo(PROVIDER_REQUEST_ID);

        assertThat(response.responseStatus())
                .isEqualTo(AiResponseStatus.COMPLETED);

        assertThat(response.finishReason())
                .isEqualTo(FINISH_REASON);

        assertThat(response.inputTokens())
                .isEqualTo(100);

        assertThat(response.outputTokens())
                .isEqualTo(50);

        assertThat(response.usageStatus())
                .isEqualTo(UsageStatus.AVAILABLE);

        assertThat(response.pricingStatus())
                .isEqualTo(PricingStatus.PRICED);

        assertThat(response.costUsd())
                .isEqualByComparingTo(PRICED_COST);

        assertThat(response.currency())
                .isEqualTo(CURRENCY_USD);

        assertThat(response.priceVersion())
                .isEqualTo(PRICE_VERSION);

        assertThat(response.pricingCalculatedAt())
                .isEqualTo(CALCULATED_AT);
    }

    @Test
    void determineUsageStatusDistinguishesAllProviderCases() {
        assertThat(
                AiChatResponse.determineUsageStatus(
                        0,
                        0
                )
        ).isEqualTo(
                UsageStatus.AVAILABLE
        );

        assertThat(
                AiChatResponse.determineUsageStatus(
                        null,
                        null
                )
        ).isEqualTo(
                UsageStatus.MISSING
        );

        assertThat(
                AiChatResponse.determineUsageStatus(
                        10,
                        null
                )
        ).isEqualTo(
                UsageStatus.PARTIAL
        );

        assertThat(
                AiChatResponse.determineUsageStatus(
                        null,
                        20
                )
        ).isEqualTo(
                UsageStatus.PARTIAL
        );
    }

    @Test
    void oversizedProviderMessageIdIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "model",
                        "model",
                        "x".repeat(256),
                        "request-id",
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerMessageId");
    }

    @Test
    void oversizedProviderRequestIdIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "model",
                        "model",
                        "message-id",
                        "x".repeat(256),
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerRequestId");
    }

    @Test
    void oversizedRequestedModelIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "x".repeat(101),
                        "model",
                        "message-id",
                        "request-id",
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedModel");
    }

    @Test
    void oversizedResolvedModelIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "model",
                        "x".repeat(101),
                        "message-id",
                        "request-id",
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void oversizedFinishReasonIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "model",
                        "model",
                        "message-id",
                        "request-id",
                        AiResponseStatus.COMPLETED,
                        "x".repeat(101),
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishReason");
    }

    @Test
    void oversizedPriceVersionIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "model",
                        "model",
                        "message-id",
                        "request-id",
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        POSITIVE_COST,
                        PricingStatus.PRICED,
                        CURRENCY_USD,
                        "x".repeat(65),
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceVersion");
    }

    @Test
    void pricedRequiresPositiveCost() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        ZERO_COST,
                        PricingStatus.PRICED,
                        CURRENCY_USD,
                        "v1",
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRICED");
    }

    @Test
    void freeRequiresZeroCost() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        POSITIVE_COST,
                        PricingStatus.FREE,
                        CURRENCY_USD,
                        "v1",
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREE");
    }

    @Test
    void nonUsdCurrencyIsRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        "message-id",
                        "request-id",
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        POSITIVE_COST,
                        PricingStatus.PRICED,
                        "EUR",
                        "v1",
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void excessiveCostScaleIsRejected() {
        BigDecimal excessiveScaleCost =
                new BigDecimal("0.0000000000001");

        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.AVAILABLE,
                        excessiveScaleCost,
                        PricingStatus.PRICED,
                        CURRENCY_USD,
                        "v1",
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
    }

    @Test
    void availableUsageRequiresBothTokenCounters() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        null,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE");
    }

    @Test
    void missingUsageRequiresBothTokenCountersToBeAbsent() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        null,
                        UsageStatus.MISSING,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    void partialUsageRequiresExactlyOneTokenCounter() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        1,
                        UsageStatus.PARTIAL,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARTIAL");
    }

    @Test
    void negativeInputTokensAreRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        -1,
                        1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputTokens");
    }

    @Test
    void negativeOutputTokensAreRejected() {
        assertThatThrownBy(
                () -> new AiChatResponse(
                        CONTENT,
                        "requested",
                        "resolved",
                        null,
                        null,
                        AiResponseStatus.COMPLETED,
                        FINISH_REASON,
                        1,
                        -1,
                        UsageStatus.AVAILABLE,
                        null,
                        PricingStatus.UNPRICED,
                        null,
                        null,
                        CALCULATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputTokens");
    }
}