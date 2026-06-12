package ru.safeai.gateway.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockAiProviderTest {

    private MockAiProvider mockAiProvider;

    @BeforeEach
    void setUp() {
        mockAiProvider = new MockAiProvider();
    }

    @Test
    void sendMessage_shouldReturnMockAiResponse() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Привет",
                List.of()
        );

        AiChatResponse response = mockAiProvider.sendMessage(request);

        assertThat(response.content()).isEqualTo("Mock AI provider response: Привет");
        assertThat(response.model()).isEqualTo("mock-safeai");
        assertThat(response.inputTokens()).isGreaterThan(0);
        assertThat(response.outputTokens()).isGreaterThan(0);
        assertThat(response.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sendMessage_shouldHandleBlankUserMessage() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "",
                List.of()
        );

        AiChatResponse response = mockAiProvider.sendMessage(request);

        assertThat(response.content()).isEqualTo("Mock AI provider response: ");
        assertThat(response.model()).isEqualTo("mock-safeai");
        assertThat(response.inputTokens()).isEqualTo(0);
        assertThat(response.outputTokens()).isGreaterThan(0);
        assertThat(response.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sendMessage_shouldIncludeHistoryInInputTokenCalculation() {
        AiChatRequest requestWithoutHistory = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Новое сообщение",
                List.of()
        );

        AiChatRequest requestWithHistory = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Новое сообщение",
                List.of(
                        new AiMessage("USER", "Старое сообщение пользователя"),
                        new AiMessage("ASSISTANT", "Старый ответ ассистента")
                )
        );

        AiChatResponse responseWithoutHistory = mockAiProvider.sendMessage(requestWithoutHistory);
        AiChatResponse responseWithHistory = mockAiProvider.sendMessage(requestWithHistory);

        assertThat(responseWithHistory.inputTokens())
                .isGreaterThan(responseWithoutHistory.inputTokens());
    }

    @Test
    void aiChatRequest_shouldReplaceNullHistoryWithEmptyList() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Привет",
                null
        );

        assertThat(request.history()).isEmpty();
    }

    @Test
    void aiChatResponse_shouldReplaceNullValuesWithDefaults() {
        AiChatResponse response = new AiChatResponse(
                null,
                null,
                null,
                null,
                null
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.model()).isEqualTo("unknown");
        assertThat(response.inputTokens()).isZero();
        assertThat(response.outputTokens()).isZero();
        assertThat(response.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}