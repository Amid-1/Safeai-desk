package ru.safeai.gateway.ai.testsupport;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiTestFixtures {

    public static final Instant NOW =
            Instant.parse("2026-08-01T12:00:00Z");

    public static final UUID USER_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001"
            );

    public static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001"
            );

    public static final UUID CHAT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001"
            );

    public static final UUID OPERATION_ID =
            UUID.fromString(
                    "40000000-0000-0000-0000-000000000001"
            );

    private static final BigDecimal ZERO_COST_USD =
            new BigDecimal("0.000000000000");

    private static final String TEST_CURRENCY =
            "USD";

    private static final String TEST_PRICING_VERSION =
            "test-v1";

    private AiTestFixtures() {
    }

    public static AiChatRequest request() {
        return new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                OPERATION_ID,
                "Platform system policy",
                "Application developer policy",
                "Current user message",
                List.of()
        );
    }

    public static AiChatResponse freeResponse() {
        PricingResult pricing =
                new PricingResult(
                        PricingStatus.FREE,
                        ZERO_COST_USD,
                        TEST_CURRENCY,
                        TEST_PRICING_VERSION,
                        NOW
                );

        return AiChatResponse.fromProvider(
                "Ответ",
                "requested-model",
                "resolved-model",
                "provider-message-id",
                "provider-request-id",
                AiResponseStatus.COMPLETED,
                "completed",
                1,
                1,
                pricing
        );
    }
}