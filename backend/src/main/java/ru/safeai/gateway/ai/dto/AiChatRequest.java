package ru.safeai.gateway.ai.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable AI provider request plus model-governance execution envelope.
 *
 * <p>Conversation history is canonical: it may be empty, otherwise it consists
 * only of complete chronological USER/ASSISTANT turns. SYSTEM and DEVELOPER
 * instructions are stored separately and must never be mixed into history.</p>
 *
 * <p>{@code reservedInputTokens} is the conservative input envelope approved by
 * model routing. {@code maxOutputTokens} is the physical output cap that must
 * reach the provider request.</p>
 *
 * <p>All immutable transformation methods must preserve identity and
 * model-governance execution metadata unless their contract explicitly says
 * otherwise.</p>
 */
public record AiChatRequest(
        UUID userId,
        UUID organizationId,
        UUID chatId,
        UUID providerOperationId,
        String systemInstructions,
        String developerInstructions,
        String userMessage,
        List<AiMessage> history,
        Long reservedInputTokens,
        Integer maxOutputTokens
) {

    private static final int ABSOLUTE_MAX_INSTRUCTION_CHARS =
            100_000;

    private static final int ABSOLUTE_MAX_USER_MESSAGE_CHARS =
            100_000;

    private static final int ABSOLUTE_MAX_HISTORY_MESSAGES =
            1_000;

    private static final long ABSOLUTE_MAX_TOTAL_CHARS =
            1_000_000L;

    public AiChatRequest {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );

        systemInstructions =
                normalizeInstructions(
                        systemInstructions,
                        "systemInstructions"
                );

        developerInstructions =
                normalizeInstructions(
                        developerInstructions,
                        "developerInstructions"
                );

        validateUserMessage(
                userMessage
        );

        history =
                history == null
                        ? List.of()
                        : List.copyOf(
                                history
                        );

        validateHistorySize(
                history
        );

        validateGovernanceEnvelope(
                reservedInputTokens,
                maxOutputTokens
        );

        long baseChars =
                Math.addExact(
                        userMessage.length(),
                        Math.addExact(
                                length(
                                        systemInstructions
                                ),
                                length(
                                        developerInstructions
                                )
                        )
                );

        /*
         * Important even when history is empty.
         * Otherwise, a request containing only very large instructions/user
         * message could bypass the aggregate text bound.
         */
        validateTotalChars(
                baseChars
        );

        validateCanonicalHistory(
                history,
                baseChars
        );
    }

    /**
     * Source compatibility for pre-V46 callers and tests.
     *
     * <p>Such requests intentionally do not carry model-route execution caps.
     * Governed production flows must use the canonical constructor with
     * {@code reservedInputTokens} and {@code maxOutputTokens}.</p>
     */
    public AiChatRequest(
            UUID userId,
            UUID organizationId,
            UUID chatId,
            UUID providerOperationId,
            String systemInstructions,
            String developerInstructions,
            String userMessage,
            List<AiMessage> history
    ) {
        this(
                userId,
                organizationId,
                chatId,
                providerOperationId,
                systemInstructions,
                developerInstructions,
                userMessage,
                history,
                null,
                null
        );
    }

    /**
     * Replaces only SYSTEM/DEVELOPER instructions while preserving:
     *
     * <ul>
     *     <li>request identity,</li>
     *     <li>provider operation identity,</li>
     *     <li>user message,</li>
     *     <li>canonical history,</li>
     *     <li>reserved input envelope,</li>
     *     <li>route-bound output cap.</li>
     * </ul>
     *
     * <p>This method should be preferred by RAG/context materialization instead
     * of manually reconstructing {@link AiChatRequest}.</p>
     */
    public AiChatRequest withInstructions(
            String systemInstructions,
            String developerInstructions
    ) {
        return new AiChatRequest(
                userId,
                organizationId,
                chatId,
                providerOperationId,
                systemInstructions,
                developerInstructions,
                userMessage,
                history,
                reservedInputTokens,
                maxOutputTokens
        );
    }

    /**
     * Replaces only canonical conversation history while preserving:
     *
     * <ul>
     *     <li>request identity,</li>
     *     <li>provider operation identity,</li>
     *     <li>instructions,</li>
     *     <li>user message,</li>
     *     <li>reserved input envelope,</li>
     *     <li>route-bound output cap.</li>
     * </ul>
     *
     * <p>This method should be preferred by context-window truncation instead
     * of manually reconstructing {@link AiChatRequest}.</p>
     */
    public AiChatRequest withHistory(
            List<AiMessage> history
    ) {
        return new AiChatRequest(
                userId,
                organizationId,
                chatId,
                providerOperationId,
                systemInstructions,
                developerInstructions,
                userMessage,
                history,
                reservedInputTokens,
                maxOutputTokens
        );
    }

    /**
     * Resolves the physical provider output limit without ever exceeding the
     * runtime/model maximum.
     *
     * <p>If the request is legacy/ungoverned and has no route-bound output
     * limit, the runtime maximum is returned unchanged.</p>
     */
    public int effectiveMaxOutputTokens(
            int runtimeMaximum
    ) {
        if (runtimeMaximum <= 0) {
            throw new IllegalArgumentException(
                    "runtimeMaximum должен быть положительным"
            );
        }

        return maxOutputTokens == null
                ? runtimeMaximum
                : Math.min(
                        runtimeMaximum,
                        maxOutputTokens
                );
    }

    private static void validateUserMessage(
            String userMessage
    ) {
        if (userMessage == null
                || userMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "userMessage не должен быть пустым"
            );
        }

        if (userMessage.length()
                > ABSOLUTE_MAX_USER_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                    "userMessage превышает абсолютный лимит "
                            + ABSOLUTE_MAX_USER_MESSAGE_CHARS
                            + " символов"
            );
        }
    }

    private static void validateHistorySize(
            List<AiMessage> history
    ) {
        if (history.size()
                > ABSOLUTE_MAX_HISTORY_MESSAGES) {
            throw new IllegalArgumentException(
                    "history превышает абсолютный лимит "
                            + ABSOLUTE_MAX_HISTORY_MESSAGES
                            + " сообщений"
            );
        }
    }

    private static void validateGovernanceEnvelope(
            Long reservedInputTokens,
            Integer maxOutputTokens
    ) {
        if (reservedInputTokens != null
                && reservedInputTokens < 0L) {
            throw new IllegalArgumentException(
                    "reservedInputTokens не может быть отрицательным"
            );
        }

        if (maxOutputTokens != null
                && maxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxOutputTokens должен быть положительным"
            );
        }
    }

    private static void validateCanonicalHistory(
            List<AiMessage> history,
            long initialTotalChars
    ) {
        if (history.isEmpty()) {
            return;
        }

        long totalChars =
                initialTotalChars;

        for (
                int index = 0;
                index < history.size();
                index++
        ) {
            AiMessage message =
                    Objects.requireNonNull(
                            history.get(index),
                            "history не должен содержать null"
                    );

            validateHistoryMessage(
                    message,
                    index
            );

            try {
                totalChars =
                        Math.addExact(
                                totalChars,
                                message.content()
                                        .length()
                        );
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "AI request превышает абсолютный лимит общего текста",
                        exception
                );
            }

            validateTotalChars(
                    totalChars
            );
        }

        /*
         * Canonical complete history is:
         *
         * USER
         * ASSISTANT
         * USER
         * ASSISTANT
         * ...
         *
         * Therefore a non-empty complete history always contains an even
         * number of messages and ends with ASSISTANT.
         */
        if ((history.size() & 1) != 0) {
            throw new IllegalArgumentException(
                    "history должен завершаться ASSISTANT"
            );
        }
    }

    private static void validateHistoryMessage(
            AiMessage message,
            int index
    ) {
        AiMessageRole actualRole =
                Objects.requireNonNull(
                        message.role(),
                        "history message role не должен быть null"
                );

        validateHistoryRole(
                actualRole,
                index
        );

        if (message.content() == null
                || message.content().isBlank()) {
            throw new IllegalArgumentException(
                    "history message content не должен быть пустым"
            );
        }
    }

    private static void validateHistoryRole(
            AiMessageRole actualRole,
            int index
    ) {
        if (actualRole != AiMessageRole.USER
                && actualRole != AiMessageRole.ASSISTANT) {
            throw new IllegalArgumentException(
                    "history должен содержать только USER/ASSISTANT. "
                            + "SYSTEM и DEVELOPER передаются отдельно"
            );
        }

        AiMessageRole expectedRole =
                expectedHistoryRole(
                        index
                );

        if (actualRole == expectedRole) {
            return;
        }

        if (index == 0) {
            throw new IllegalArgumentException(
                    "history должен начинаться с USER"
            );
        }

        throw new IllegalArgumentException(
                "history должен чередовать USER/ASSISTANT"
        );
    }

    private static AiMessageRole expectedHistoryRole(
            int index
    ) {
        return (index & 1) == 0
                ? AiMessageRole.USER
                : AiMessageRole.ASSISTANT;
    }

    private static void validateTotalChars(
            long totalChars
    ) {
        if (totalChars
                > ABSOLUTE_MAX_TOTAL_CHARS) {
            throw new IllegalArgumentException(
                    "AI request превышает абсолютный лимит общего текста"
            );
        }
    }

    private static String normalizeInstructions(
            String value,
            String field
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        String normalized =
                value.trim();

        if (normalized.length()
                > ABSOLUTE_MAX_INSTRUCTION_CHARS) {
            throw new IllegalArgumentException(
                    field
                            + " превышает абсолютный лимит "
                            + ABSOLUTE_MAX_INSTRUCTION_CHARS
                            + " символов"
            );
        }

        return normalized;
    }

    private static int length(
            String value
    ) {
        return value == null
                ? 0
                : value.length();
    }
}