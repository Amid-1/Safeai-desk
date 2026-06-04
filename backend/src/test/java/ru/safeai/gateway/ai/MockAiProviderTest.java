package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockAiProviderTest {

    private final MockAiProvider mockAiProvider = new MockAiProvider();

    @Test
    void sendMessage_shouldReturnMockResponseWithModelTokensAndCost() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Проверь, что ответ приходит через AiProvider",
                List.of(new AiMessage("USER", "Проверь, что ответ приходит через AiProvider"))
        );

        AiChatResponse response = mockAiProvider.sendMessage(request);

        assertThat(response.content())
                .isEqualTo("Mock AI provider response: Проверь, что ответ приходит через AiProvider");

        assertThat(response.model())
                .isEqualTo("mock-safeai");

        assertThat(response.inputTokens())
                .isNotNull()
                .isPositive();

        assertThat(response.outputTokens())
                .isNotNull()
                .isPositive();

        assertThat(response.costUsd())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}