package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.provider.AiProviderSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tokenExtractionPreservesMissingAndRealZero() {
        JsonNode missing = objectMapper.readTree("""
                {"id":"resp_1"}
                """);

        JsonNode zero = objectMapper.readTree("""
                {
                  "usage": {
                    "input_tokens": 0,
                    "output_tokens": 0
                  }
                }
                """);

        assertThat(
                AiProviderSupport.extractInputTokens(missing)
        ).isNull();

        assertThat(
                AiProviderSupport.extractOutputTokens(missing)
        ).isNull();

        assertThat(
                AiProviderSupport.extractInputTokens(zero)
        ).isZero();

        assertThat(
                AiProviderSupport.extractOutputTokens(zero)
        ).isZero();
    }

    @Test
    void supportsPromptAndCompletionFallbackNames() {
        JsonNode response = objectMapper.readTree("""
                {
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 34
                  }
                }
                """);

        assertThat(
                AiProviderSupport.extractInputTokens(response)
        ).isEqualTo(12);

        assertThat(
                AiProviderSupport.extractOutputTokens(response)
        ).isEqualTo(34);
    }

    @Test
    void buildMessagesExcludesConfiguredRolesAndAppendsCurrentUserMessage() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Новый вопрос",
                List.of(
                        new AiMessage(
                                AiMessageRole.SYSTEM,
                                "Системный prompt"
                        ),
                        new AiMessage(
                                AiMessageRole.USER,
                                "Старый вопрос"
                        ),
                        new AiMessage(
                                AiMessageRole.ASSISTANT,
                                "Старый ответ"
                        )
                )
        );

        List<Map<String, String>> messages =
                AiProviderSupport.buildMessages(
                        request,
                        AiMessageRole::providerValue,
                        Set.of(AiMessageRole.SYSTEM)
                );

        assertThat(messages)
                .extracting(item -> item.get("content"))
                .containsExactly(
                        "Старый вопрос",
                        "Старый ответ",
                        "Новый вопрос"
                );
    }

    @Test
    void extractsJoinedSystemPrompt() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Вопрос",
                List.of(
                        new AiMessage(
                                AiMessageRole.SYSTEM,
                                "Первый"
                        ),
                        new AiMessage(
                                AiMessageRole.USER,
                                "История"
                        ),
                        new AiMessage(
                                AiMessageRole.SYSTEM,
                                "Второй"
                        )
                )
        );

        assertThat(
                AiProviderSupport.extractSystemPrompt(request)
        ).isEqualTo("Первый\n\nВторой");
    }
}