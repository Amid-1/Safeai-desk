package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AiHistoryBuilder {

    public List<AiMessage> build(
            List<ChatMessageEntity> newestFirst,
            int tokenBudget
    ) {
        if (newestFirst == null
                || newestFirst.isEmpty()
                || tokenBudget <= 0) {
            return List.of();
        }

        List<ChatMessageEntity> selectedNewestFirst =
                selectWithinTokenBudget(
                        newestFirst,
                        tokenBudget
                );

        if (selectedNewestFirst.isEmpty()) {
            return List.of();
        }

        Collections.reverse(selectedNewestFirst);

        removeLeadingAssistantMessages(selectedNewestFirst);

        if (selectedNewestFirst.isEmpty()) {
            return List.of();
        }

        return selectedNewestFirst.stream()
                .map(this::toAiMessage)
                .toList();
    }

    private List<ChatMessageEntity> selectWithinTokenBudget(
            List<ChatMessageEntity> newestFirst,
            int tokenBudget
    ) {
        List<ChatMessageEntity> selected =
                new ArrayList<>();

        int usedTokens = 0;

        for (ChatMessageEntity message : newestFirst) {
            if (!isEligible(message)) {
                continue;
            }

            int estimatedTokens =
                    estimateTokens(message.getContent());

            if (estimatedTokens > tokenBudget - usedTokens) {
                continue;
            }

            selected.add(message);
            usedTokens += estimatedTokens;

            if (usedTokens >= tokenBudget) {
                break;
            }
        }

        return selected;
    }

    private void removeLeadingAssistantMessages(
            List<ChatMessageEntity> chronological
    ) {
        while (!chronological.isEmpty()
                && chronological.getFirst().getRole()
                == ChatMessageRole.ASSISTANT) {
            chronological.removeFirst();
        }
    }

    private boolean isEligible(ChatMessageEntity message) {
        if (message == null
                || message.getStatus()
                != ChatMessageStatus.COMPLETED
                || message.getContent() == null
                || message.getContent().isBlank()) {
            return false;
        }

        return message.getRole() == ChatMessageRole.USER
                || message.getRole()
                == ChatMessageRole.ASSISTANT;
    }

    private AiMessage toAiMessage(
            ChatMessageEntity message
    ) {
        AiMessageRole role = switch (message.getRole()) {
            case USER -> AiMessageRole.USER;
            case ASSISTANT -> AiMessageRole.ASSISTANT;

            case SYSTEM -> throw new IllegalStateException(
                    "SYSTEM message must not reach ordinary chat history"
            );
        };

        return new AiMessage(
                role,
                message.getContent()
        );
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        return Math.max(1, content.length() / 4);
    }
}

