package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.provider.AiContextWindowProperties;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.chat.repository.ChatHistoryTurn;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiHistoryBuilderTest {

    private final AiHistoryBuilder builder = new AiHistoryBuilder();

    @Test
    void restoresChronologicalCompleteTurns() {
        List<AiMessage> result = builder.build(List.of(
                turn("new-user", "new-assistant"),
                turn("old-user", "old-assistant")
        ));

        assertThat(result)
                .extracting(AiMessage::content)
                .containsExactly(
                        "old-user",
                        "old-assistant",
                        "new-user",
                        "new-assistant"
                );
    }

    @Test
    void historyAlwaysAlternatesUserAndAssistant() {
        assertThat(builder.build(List.of(
                turn("u1", "a1"),
                turn("u0", "a0")
        )))
                .extracting(AiMessage::role)
                .containsExactly(
                        AiMessageRole.USER,
                        AiMessageRole.ASSISTANT,
                        AiMessageRole.USER,
                        AiMessageRole.ASSISTANT
                );
    }

    @Test
    void incompleteSucceededTurnFailsFast() {
        assertThatThrownBy(() -> builder.build(List.of(
                new ChatHistoryTurn(UUID.randomUUID(), "user", null)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USER/ASSISTANT");
    }

    @Test
    void nullHistoryIsEmpty() {
        assertThat(builder.build(null)).isEmpty();
    }

    @Test
    void currentMessageIsNotDuplicatedInHistory() {
        List<AiMessage> history = builder.build(List.of(
                turn("previous-question", "previous-answer")
        ));
        AiChatRequest request = request("current-question", history);

        assertThat(request.history())
                .extracting(AiMessage::content)
                .doesNotContain("current-question");
    }

    @Test
    void contextTruncationKeepsWholeNewestTurnAndStopsAtOversizedBoundary() {
        List<AiMessage> history = builder.build(List.of(
                turn("new-u", "new-a"),
                turn("x".repeat(200), "old-a")
        ));
        AiContextWindowService window = new AiContextWindowService(
                new AiContextWindowProperties(
                        1000,
                        1000,
                        100,
                        1000,
                        1000,
                        1,
                        0,
                        0
                )
        );

        AiChatRequest prepared = window.prepare(
                request("current", history),
                30,
                0
        );

        assertThat(prepared.history())
                .extracting(AiMessage::content)
                .containsExactly("new-u", "new-a");
    }

    private static ChatHistoryTurn turn(String user, String assistant) {
        return new ChatHistoryTurn(UUID.randomUUID(), user, assistant);
    }

    private static AiChatRequest request(
            String current,
            List<AiMessage> history
    ) {
        return new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                current,
                history
        );
    }
}
