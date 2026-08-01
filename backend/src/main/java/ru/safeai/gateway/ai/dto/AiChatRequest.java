package ru.safeai.gateway.ai.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public record AiChatRequest(
        UUID userId,
        UUID organizationId,
        UUID chatId,
        UUID providerOperationId,
        String systemInstructions,
        String developerInstructions,
        String userMessage,
        List<AiMessage> history
) {
    private static final int ABSOLUTE_MAX_INSTRUCTION_CHARS = 100_000;
    private static final int ABSOLUTE_MAX_USER_MESSAGE_CHARS = 100_000;
    private static final int ABSOLUTE_MAX_HISTORY_MESSAGES = 1_000;
    private static final long ABSOLUTE_MAX_TOTAL_CHARS = 1_000_000L;

    public AiChatRequest {
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );

        systemInstructions = normalizeInstructions(
                systemInstructions,
                "systemInstructions"
        );
        developerInstructions = normalizeInstructions(
                developerInstructions,
                "developerInstructions"
        );

        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "userMessage не должен быть пустым"
            );
        }
        if (userMessage.length() > ABSOLUTE_MAX_USER_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                    "userMessage превышает абсолютный лимит "
                            + ABSOLUTE_MAX_USER_MESSAGE_CHARS
                            + " символов"
            );
        }

        history = history == null
                ? List.of()
                : List.copyOf(history);

        if (history.size() > ABSOLUTE_MAX_HISTORY_MESSAGES) {
            throw new IllegalArgumentException(
                    "history превышает абсолютный лимит "
                            + ABSOLUTE_MAX_HISTORY_MESSAGES
                            + " сообщений"
            );
        }

        long totalChars = userMessage.length()
                + length(systemInstructions)
                + length(developerInstructions);

        AiMessageRole previousRole = null;

        for (int index = 0; index < history.size(); index++) {
            AiMessage message = Objects.requireNonNull(
                    history.get(index),
                    "history не должен содержать null"
            );

            if (message.role() != AiMessageRole.USER
                    && message.role() != AiMessageRole.ASSISTANT) {
                throw new IllegalArgumentException(
                        "history должен содержать только USER/ASSISTANT. "
                                + "SYSTEM и DEVELOPER передаются отдельно"
                );
            }

            if (index == 0 && message.role() != AiMessageRole.USER) {
                throw new IllegalArgumentException(
                        "history должен начинаться с USER"
                );
            }

            if (message.role() == previousRole) {
                throw new IllegalArgumentException(
                        "history должен чередовать USER и ASSISTANT"
                );
            }

            previousRole = message.role();
            totalChars += message.content().length();

            if (totalChars > ABSOLUTE_MAX_TOTAL_CHARS) {
                throw new IllegalArgumentException(
                        "AI request превышает абсолютный лимит "
                                + ABSOLUTE_MAX_TOTAL_CHARS
                                + " символов"
                );
            }
        }

        if (!history.isEmpty()
                && history.getLast().role() != AiMessageRole.ASSISTANT) {
            throw new IllegalArgumentException(
                    "history перед новым userMessage должен завершаться ASSISTANT"
            );
        }
    }

    /**
     * Совместимость с прежним контрактом: SYSTEM и DEVELOPER извлекаются
     * из legacy history и больше не теряются в Anthropic.
     */
    public AiChatRequest(
            UUID userId,
            UUID organizationId,
            UUID chatId,
            UUID providerOperationId,
            String userMessage,
            List<AiMessage> legacyHistory
    ) {
        this(
                userId,
                organizationId,
                chatId,
                providerOperationId,
                joinInstructions(legacyHistory, AiMessageRole.SYSTEM),
                joinInstructions(legacyHistory, AiMessageRole.DEVELOPER),
                userMessage,
                conversationHistory(legacyHistory)
        );
    }

    public AiChatRequest(
            UUID userId,
            UUID organizationId,
            UUID chatId,
            String userMessage,
            List<AiMessage> legacyHistory
    ) {
        this(
                userId,
                organizationId,
                chatId,
                UUID.randomUUID(),
                userMessage,
                legacyHistory
        );
    }

    public AiChatRequest(
            UUID userId,
            UUID organizationId,
            UUID chatId,
            String systemInstructions,
            String developerInstructions,
            String userMessage,
            List<AiMessage> history
    ) {
        this(
                userId,
                organizationId,
                chatId,
                UUID.randomUUID(),
                systemInstructions,
                developerInstructions,
                userMessage,
                history
        );
    }

    private static String normalizeInstructions(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.length() > ABSOLUTE_MAX_INSTRUCTION_CHARS) {
            throw new IllegalArgumentException(
                    fieldName + " превышает абсолютный лимит "
                            + ABSOLUTE_MAX_INSTRUCTION_CHARS
                            + " символов"
            );
        }

        return normalized;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String joinInstructions(
            List<AiMessage> history,
            AiMessageRole role
    ) {
        if (history == null || history.isEmpty()) {
            return null;
        }

        String joined = history.stream()
                .filter(Objects::nonNull)
                .filter(message -> message.role() == role)
                .map(AiMessage::content)
                .collect(Collectors.joining("\n\n"));

        return joined.isBlank() ? null : joined;
    }

    private static List<AiMessage> conversationHistory(
            List<AiMessage> history
    ) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<AiMessage> result = new ArrayList<>();

        for (AiMessage message : history) {
            if (message == null) {
                throw new IllegalArgumentException(
                        "history не должен содержать null"
                );
            }

            if (message.role() == AiMessageRole.USER
                    || message.role() == AiMessageRole.ASSISTANT) {
                result.add(message);
            }
        }

        return List.copyOf(result);
    }
}
