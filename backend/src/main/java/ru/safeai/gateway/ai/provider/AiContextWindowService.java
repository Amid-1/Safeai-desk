package ru.safeai.gateway.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.exception.AiContextLimitException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AiContextWindowService {

    private final AiContextWindowProperties properties;

    public AiContextWindowService(
            AiContextWindowProperties properties
    ) {
        this.properties = properties;
    }

    public static AiContextWindowService defaults() {
        return new AiContextWindowService(
                AiContextWindowProperties.defaults()
        );
    }

    public AiChatRequest prepare(
            AiChatRequest request,
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

        validateMandatoryFields(request);
        validateHistoryMessages(request.history());

        int tokenBudget = maxInputTokens
                - reservedOutputTokens
                - properties.safetyMarginTokens();

        if (tokenBudget < 1) {
            throw new AiContextLimitException(
                    "AI context budget исчерпан зарезервированным output"
            );
        }

        int mandatoryTokens = estimateMandatoryTokens(request);
        int mandatoryChars = mandatoryChars(request);

        if (mandatoryTokens > tokenBudget
                || mandatoryChars > properties.maxTotalInputChars()) {
            throw new AiContextLimitException(
                    "Обязательные system/developer instructions "
                            + "и текущее сообщение не помещаются "
                            + "в разрешённый AI context"
            );
        }

        int remainingTokens = tokenBudget - mandatoryTokens;
        int remainingChars =
                properties.maxTotalInputChars() - mandatoryChars;

        List<AiMessage> retainedReversed = new ArrayList<>();
        List<AiMessage> history = request.history();

        for (int index = history.size() - 1;
                index >= 0
                        && retainedReversed.size()
                        < properties.maxHistoryMessages();
                index--) {
            AiMessage message = history.get(index);
            int chars = message.content().length();
            int tokens = estimateMessageTokens(message);

            if (chars > remainingChars || tokens > remainingTokens) {
                break;
            }

            retainedReversed.add(message);
            remainingChars -= chars;
            remainingTokens -= tokens;
        }

        /*
         * Исходная история валидна как USER/ASSISTANT пары и заканчивается
         * ASSISTANT. При усечении сохраняем только целые пары, чтобы новый
         * provider request не начинался с orphan ASSISTANT.
         */
        if ((retainedReversed.size() & 1) != 0) {
            retainedReversed.removeLast();
        }

        List<AiMessage> retained = new ArrayList<>(
                retainedReversed.size()
        );

        for (int index = retainedReversed.size() - 1;
                index >= 0;
                index--) {
            retained.add(retainedReversed.get(index));
        }

        if (retained.size() != history.size()) {
            log.debug(
                    "AI history truncated: operationId={}, originalMessages={}, "
                            + "retainedMessages={}, maxInputTokens={}, "
                            + "reservedOutputTokens={}",
                    request.providerOperationId(),
                    history.size(),
                    retained.size(),
                    maxInputTokens,
                    reservedOutputTokens
            );
        }

        return new AiChatRequest(
                request.userId(),
                request.organizationId(),
                request.chatId(),
                request.providerOperationId(),
                request.systemInstructions(),
                request.developerInstructions(),
                request.userMessage(),
                retained
        );
    }

    private void validateMandatoryFields(AiChatRequest request) {
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

    private void requireMaxLength(
            String value,
            int maxLength,
            String fieldName
    ) {
        if (value != null && value.length() > maxLength) {
            throw new AiContextLimitException(
                    fieldName + " превышает " + maxLength + " символов"
            );
        }
    }

    private int mandatoryChars(AiChatRequest request) {
        return request.userMessage().length()
                + length(request.systemInstructions())
                + length(request.developerInstructions());
    }

    private int estimateMandatoryTokens(AiChatRequest request) {
        int result = estimateTextTokens(request.userMessage())
                + properties.messageOverheadTokens();

        if (request.systemInstructions() != null) {
            result += estimateTextTokens(request.systemInstructions())
                    + properties.messageOverheadTokens();
        }

        if (request.developerInstructions() != null) {
            result += estimateTextTokens(request.developerInstructions())
                    + properties.messageOverheadTokens();
        }

        return result;
    }

    private int estimateMessageTokens(AiMessage message) {
        return estimateTextTokens(message.content())
                + properties.messageOverheadTokens();
    }

    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int charsPerToken = properties.charsPerEstimatedToken();
        return Math.max(
                1,
                (text.length() + charsPerToken - 1) / charsPerToken
        );
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
