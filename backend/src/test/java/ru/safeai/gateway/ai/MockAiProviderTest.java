package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.provider.mock.MockAiProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class MockAiProviderTest {

    private static final Instant NOW =
            Instant.parse("2026-07-12T12:00:00Z");

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID CHAT_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID PROVIDER_OPERATION_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    @Test
    void returnsAvailableFreeVersionedResponse() {
        AiChatResponse response =
                provider().sendMessage(
                        new AiChatRequest(
                                USER_ID,
                                ORGANIZATION_ID,
                                CHAT_ID,
                                PROVIDER_OPERATION_ID,
                                "system",
                                "developer",
                                "Привет",
                                List.of()
                        )
                );

        assertThat(response.content())
                .isEqualTo(
                        "Mock AI provider response: Привет"
                );

        assertThat(response.requestedModel())
                .isEqualTo("mock-safeai");

        assertThat(response.model())
                .isEqualTo("mock-safeai");

        assertThat(response.providerRequestId())
                .isNotBlank();

        assertThat(response.responseStatus())
                .isEqualTo(
                        AiResponseStatus.COMPLETED
                );

        assertThat(response.usageStatus())
                .isEqualTo(
                        UsageStatus.AVAILABLE
                );

        assertThat(response.pricingStatus())
                .isEqualTo(
                        PricingStatus.FREE
                );

        assertThat(response.costUsd())
                .isEqualByComparingTo(
                        BigDecimal.ZERO
                );

        assertThat(response.costUsd().scale())
                .isEqualTo(12);
    }

    @Test
    void includesInstructionsAndHistoryInTokenEstimate() {
        MockAiProvider provider =
                provider();

        AiChatResponse minimal =
                provider.sendMessage(
                        request(
                                null,
                                null,
                                List.of()
                        )
                );

        AiChatResponse enriched =
                provider.sendMessage(
                        request(
                                "system instructions",
                                "developer instructions",
                                List.of(
                                        new AiMessage(
                                                AiMessageRole.USER,
                                                "old user"
                                        ),
                                        new AiMessage(
                                                AiMessageRole.ASSISTANT,
                                                "old answer"
                                        )
                                )
                        )
                );

        assertThat(enriched.inputTokens())
                .isGreaterThan(
                        minimal.inputTokens()
                );
    }

    private AiChatRequest request(
            String system,
            String developer,
            List<AiMessage> history
    ) {
        return new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                PROVIDER_OPERATION_ID,
                system,
                developer,
                "new message",
                history
        );
    }

    private MockAiProvider provider() {
        ModelPricingProperties properties =
                new ModelPricingProperties(
                        List.of(
                                new ModelPricingProperties.ModelPrice(
                                        "mock-safeai",
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        "USD",
                                        "mock-2026-01"
                                )
                        )
                );

        return new MockAiProvider(
                new ModelPricingService(
                        properties,
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC
                        )
                )
        );
    }
}