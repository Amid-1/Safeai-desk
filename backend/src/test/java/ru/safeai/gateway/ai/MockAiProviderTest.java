package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.provider.mock.MockAiProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAiProviderTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private final MockAiProvider provider = new MockAiProvider();

    @Test
    void sendMessage_shouldReturnMockResponse() {
        AiChatRequest request = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                List.of()
        );

        AiChatResponse response = provider.sendMessage(request);

        assertThat(response.content())
                .isEqualTo("Mock AI provider response: Привет");

        assertThat(response.model())
                .isEqualTo("mock-safeai");

        assertThat(response.inputTokens())
                .isGreaterThanOrEqualTo(1);

        assertThat(response.outputTokens())
                .isGreaterThanOrEqualTo(1);

        assertThat(response.costUsd())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sendMessage_shouldIncludeHistoryTokensInInputTokens() {
        AiChatRequest request = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Новое сообщение",
                List.of(
                        new AiMessage("USER", "Старое сообщение"),
                        new AiMessage("ASSISTANT", "Старый ответ")
                )
        );

        AiChatResponse response = provider.sendMessage(request);

        assertThat(response.inputTokens())
                .isGreaterThan(estimateTokens("Новое сообщение"));

        assertThat(response.outputTokens())
                .isEqualTo(estimateTokens(response.content()));
    }

    @Test
    void sendMessage_shouldHandleNullHistoryAsEmptyHistory() {
        AiChatRequest request = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                null
        );

        AiChatResponse response = provider.sendMessage(request);

        assertThat(response.content())
                .isEqualTo("Mock AI provider response: Привет");

        assertThat(response.inputTokens())
                .isEqualTo(estimateTokens("Привет"));
    }

    @Test
    void aiChatRequest_shouldRejectBlankUserMessage() {
        assertThatThrownBy(() -> new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "   ",
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userMessage не должен быть пустым");
    }

    @Test
    void aiChatRequest_shouldRejectNullUserMessage() {
        assertThatThrownBy(() -> new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                null,
                List.of()
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userMessage не должен быть null");
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        return Math.max(1, text.length() / 4);
    }
}