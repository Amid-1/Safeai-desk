package ru.safeai.gateway.ai;

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

class MockAiProviderTest {

    private static final Instant NOW =
            Instant.parse("2026-07-12T12:00:00Z");

    @Test
    void returnsAvailableFreeVersionedResponse() {
        MockAiProvider provider = provider();

        AiChatResponse response = provider.sendMessage(
                new AiChatRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Привет",
                        List.of()
                )
        );

        assertThat(response.content())
                .isEqualTo("Mock AI provider response: Привет");
        assertThat(response.model()).isEqualTo("mock-safeai");
        assertThat(response.responseStatus())
                .isEqualTo(AiResponseStatus.COMPLETED);
        assertThat(response.usageStatus())
                .isEqualTo(UsageStatus.AVAILABLE);
        assertThat(response.pricingStatus())
                .isEqualTo(PricingStatus.FREE);
        assertThat(response.costUsd()).isEqualByComparingTo("0.000000");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.priceVersion()).isEqualTo("mock-2026-01");
        assertThat(response.pricingCalculatedAt()).isEqualTo(NOW);
    }

    @Test
    void includesHistoryInInputTokenEstimate() {
        MockAiProvider provider = provider();

        AiChatResponse withoutHistory = provider.sendMessage(
                request(List.of())
        );

        AiChatResponse withHistory = provider.sendMessage(
                request(List.of(
                        new AiMessage(AiMessageRole.USER, "Старое сообщение"),
                        new AiMessage(AiMessageRole.ASSISTANT, "Старый ответ")
                ))
        );

        assertThat(withHistory.inputTokens())
                .isGreaterThan(withoutHistory.inputTokens());
    }

    private AiChatRequest request(List<AiMessage> history) {
        return new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Новое сообщение",
                history
        );
    }

    private MockAiProvider provider() {
        ModelPricingProperties properties =
                new ModelPricingProperties(List.of(
                        new ModelPricingProperties.ModelPrice(
                                "mock-safeai",
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "USD",
                                "mock-2026-01"
                        )
                ));

        return new MockAiProvider(
                new ModelPricingService(
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                )
        );
    }
}
