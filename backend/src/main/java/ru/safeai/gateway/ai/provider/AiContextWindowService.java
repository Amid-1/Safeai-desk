package ru.safeai.gateway.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.exception.AiContextLimitException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class AiContextWindowService {

    private final AiContextWindowProperties properties;

    public AiContextWindowService(
            AiContextWindowProperties properties
    ) {
        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );
    }

    public AiChatRequest prepare(
            AiChatRequest request,
            int maxInputTokens,
            int reservedOutputTokens
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        validateMandatoryFields(
                request
        );

        validateHistoryMessages(
                request.history()
        );

        int tokenBudget =
                calculateTokenBudget(
                        maxInputTokens,
                        reservedOutputTokens
                );

        int mandatoryTokens =
                estimateMandatoryTokens(
                        request
                );

        int mandatoryChars =
                mandatoryChars(
                        request
                );

        if (mandatoryTokens > tokenBudget
                || mandatoryChars
                > properties.maxTotalInputChars()) {
            throw new AiContextLimitException(
                    "Обязательные system/developer instructions "
                            + "и текущее сообщение не помещаются "
                            + "в разрешённый AI context"
            );
        }

        int remainingTokens =
                tokenBudget
                        - mandatoryTokens;

        int remainingChars =
                properties.maxTotalInputChars()
                        - mandatoryChars;

        List<AiMessage> history =
                request.history();

        List<AiMessage> retainedReversed =
                new ArrayList<>(
                        Math.min(
                                history.size(),
                                properties.maxHistoryMessages()
                        )
                );

        /*
         * Берём максимально свежую историю с конца.
         *
         * AiChatRequest гарантирует canonical history:
         *
         * USER
         * ASSISTANT
         * USER
         * ASSISTANT
         * ...
         *
         * Непустая история заканчивается ASSISTANT.
         */
        for (
                int index = history.size() - 1;
                index >= 0
                        && retainedReversed.size()
                        < properties.maxHistoryMessages();
                index--
        ) {
            AiMessage message =
                    history.get(
                            index
                    );

            int chars =
                    message.content()
                            .length();

            int tokens =
                    estimateMessageTokens(
                            message
                    );

            if (chars > remainingChars
                    || tokens > remainingTokens) {
                break;
            }

            retainedReversed.add(
                    message
            );

            remainingChars -=
                    chars;

            remainingTokens -=
                    tokens;
        }

        /*
         * Обход выполняется с конца, поэтому первым сохранённым сообщением
         * является ASSISTANT.
         *
         * Нечётное количество означает orphan ASSISTANT без USER.
         * Оставляем только полные USER/ASSISTANT пары.
         */
        if ((retainedReversed.size() & 1) != 0) {
            retainedReversed.removeLast();
        }

        Collections.reverse(
                retainedReversed
        );

        List<AiMessage> retained =
                List.copyOf(
                        retainedReversed
                );

        if (retained.size()
                != history.size()) {
            log.debug(
                    "AI history truncated: operationId={}, "
                            + "originalMessages={}, retainedMessages={}, "
                            + "maxInputTokens={}, reservedOutputTokens={}",
                    request.providerOperationId(),
                    history.size(),
                    retained.size(),
                    maxInputTokens,
                    reservedOutputTokens
            );
        }

        /*
         * Критический model-governance invariant.
         *
         * Нельзя реконструировать AiChatRequest через legacy constructor:
         * должны сохраниться:
         *
         * - providerOperationId
         * - reservedInputTokens
         * - maxOutputTokens
         *
         * Меняем только history.
         */
        return request.withHistory(
                retained
        );
    }

    private int calculateTokenBudget(
            int maxInputTokens,
            int reservedOutputTokens
    ) {
        if (maxInputTokens < 1) {
            throw new IllegalArgumentException(
                    "maxInputTokens должен быть положительным"
            );
        }

        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens не может быть отрицательным"
            );
        }

        long tokenBudget =
                (long) maxInputTokens
                        - reservedOutputTokens
                        - properties.safetyMarginTokens();

        if (tokenBudget < 1L) {
            throw new AiContextLimitException(
                    "AI context budget исчерпан зарезервированным output"
            );
        }

        /*
         * tokenBudget не может превышать maxInputTokens, поэтому после
         * проверки положительности безопасно конвертируется в int.
         */
        return Math.toIntExact(
                tokenBudget
        );
    }

    private void validateMandatoryFields(
            AiChatRequest request
    ) {
        requireMaxLength(
                request.userMessage(),
                properties.maxUserMessageChars(),
                "userMessage"
        );

        requireMaxLength(
                request.systemInstructions(),
                properties.maxInstructionChars(),
                "systemInstructions"
        );

        requireMaxLength(
                request.developerInstructions(),
                properties.maxInstructionChars(),
                "developerInstructions"
        );
    }

    private void validateHistoryMessages(
            List<AiMessage> history
    ) {
        for (AiMessage message : history) {
            requireMaxLength(
                    message.content(),
                    properties.maxMessageChars(),
                    "history message"
            );
        }
    }

    private static void requireMaxLength(
            String value,
            int maxLength,
            String fieldName
    ) {
        if (value != null
                && value.length() > maxLength) {
            throw new AiContextLimitException(
                    fieldName
                            + " превышает "
                            + maxLength
                            + " символов"
            );
        }
    }

    private int mandatoryChars(
            AiChatRequest request
    ) {
        long total =
                (long) request.userMessage()
                        .length()
                        + length(
                                request.systemInstructions()
                        )
                        + length(
                                request.developerInstructions()
                        );

        if (total > Integer.MAX_VALUE) {
            throw new AiContextLimitException(
                    "Обязательный AI context превышает допустимый размер"
            );
        }

        return (int) total;
    }

    private int estimateMandatoryTokens(
            AiChatRequest request
    ) {
        int result =
                estimateTextMessageTokens(
                        request.userMessage()
                );

        if (request.systemInstructions()
                != null) {
            result =
                    addTokens(
                            result,
                            estimateTextMessageTokens(
                                    request.systemInstructions()
                            )
                    );
        }

        if (request.developerInstructions()
                != null) {
            result =
                    addTokens(
                            result,
                            estimateTextMessageTokens(
                                    request.developerInstructions()
                            )
                    );
        }

        return result;
    }

    private int estimateMessageTokens(
            AiMessage message
    ) {
        return estimateTextMessageTokens(
                message.content()
        );
    }

    private int estimateTextMessageTokens(
            String text
    ) {
        return addTokens(
                estimateTextTokens(
                        text
                ),
                properties.messageOverheadTokens()
        );
    }

    private int estimateTextTokens(
            String text
    ) {
        if (text == null
                || text.isEmpty()) {
            return 0;
        }

        int charsPerToken =
                properties.charsPerEstimatedToken();

        if (charsPerToken <= 0) {
            throw new IllegalStateException(
                    "charsPerEstimatedToken должен быть положительным"
            );
        }

        return Math.max(
                1,
                Math.ceilDiv(
                        text.length(),
                        charsPerToken
                )
        );
    }

    private static int addTokens(
            int left,
            int right
    ) {
        try {
            return Math.addExact(
                    left,
                    right
            );
        } catch (ArithmeticException exception) {
            throw new AiContextLimitException(
                    "Расчёт AI context превысил допустимый диапазон"
            );
        }
    }

    private static int length(
            String value
    ) {
        return value == null
                ? 0
                : value.length();
    }
}