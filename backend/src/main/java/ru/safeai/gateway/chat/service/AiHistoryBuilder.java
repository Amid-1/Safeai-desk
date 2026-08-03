package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.chat.repository.ChatHistoryTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an alternating chronological history from terminally successful
 * turns only. Token-window truncation is delegated to AiContextWindowService,
 * which removes complete USER/ASSISTANT pairs from the oldest boundary.
 */
@Component
public class AiHistoryBuilder {

    public List<AiMessage> build(
            List<ChatHistoryTurn> newestFirst
    ) {
        if (newestFirst == null || newestFirst.isEmpty()) {
            return List.of();
        }

        List<AiMessage> result = new ArrayList<>(newestFirst.size() * 2);
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            ChatHistoryTurn turn = newestFirst.get(index);
            if (turn == null
                    || turn.userContent() == null
                    || turn.userContent().isBlank()
                    || turn.assistantContent() == null
                    || turn.assistantContent().isBlank()) {
                throw new IllegalStateException(
                        "SUCCEEDED history turn должен содержать полную USER/ASSISTANT пару"
                );
            }
            result.add(new AiMessage(AiMessageRole.USER, turn.userContent()));
            result.add(
                    new AiMessage(
                            AiMessageRole.ASSISTANT,
                            turn.assistantContent()
                    )
            );
        }
        return List.copyOf(result);
    }
}
