package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiHistoryBuilderTest {

    private final AiHistoryBuilder builder = new AiHistoryBuilder();

    @Test
    void buildRestoresChronologicalOrder() {
        ChatMessageEntity newest = message(
                ChatMessageRole.ASSISTANT,
                "Ответ",
                Instant.parse("2026-06-12T12:01:00Z")
        );

        ChatMessageEntity oldest = message(
                ChatMessageRole.USER,
                "Вопрос",
                Instant.parse("2026-06-12T12:00:00Z")
        );

        List<AiMessage> result = builder.build(
                List.of(newest, oldest),
                1_000
        );

        assertThat(result)
                .extracting(AiMessage::content)
                .containsExactly("Вопрос", "Ответ");
    }

    @Test
    void buildDoesNotStartWithAssistantMessage() {
        ChatMessageEntity newestUser = message(
                ChatMessageRole.USER,
                "Новый вопрос",
                Instant.parse("2026-06-12T12:02:00Z")
        );

        ChatMessageEntity olderAssistant = message(
                ChatMessageRole.ASSISTANT,
                "Старый ответ",
                Instant.parse("2026-06-12T12:01:00Z")
        );

        List<AiMessage> result = builder.build(
                List.of(newestUser, olderAssistant),
                1_000
        );

        assertThat(result)
                .extracting(AiMessage::role)
                .containsExactly(AiMessageRole.USER);
    }

    @Test
    void buildRespectsTokenBudget() {
        ChatMessageEntity newest = message(
                ChatMessageRole.USER,
                "12345678",
                Instant.parse("2026-06-12T12:01:00Z")
        );

        ChatMessageEntity older = message(
                ChatMessageRole.USER,
                "abcdefgh",
                Instant.parse("2026-06-12T12:00:00Z")
        );

        List<AiMessage> result = builder.build(
                List.of(newest, older),
                2
        );

        assertThat(result)
                .extracting(AiMessage::content)
                .containsExactly("12345678");
    }

    private ChatMessageEntity message(
            ChatMessageRole role,
            String content,
            Instant createdAt
    ) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setRole(role);
        message.setContent(content);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setCreatedAt(createdAt);
        return message;
    }
}