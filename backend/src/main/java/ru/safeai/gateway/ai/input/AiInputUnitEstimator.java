package ru.safeai.gateway.ai.input;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Canonical deterministic governance accounting for the current text data plane.
 *
 * <p>Input units are intentionally not called provider tokens. The estimator
 * accounts UTF-8 payload bytes plus a conservative per-message framing
 * allowance. Model
 * routing, RAG fitting and the final pre-provider guard must all call this
 * implementation, so there is only one accounting semantic.</p>
 *
 * <p>When tools, multimodal input or structured-output schemas become part of
 * the provider request, extend this estimator and advance {@link #VERSION}
 * before enabling the corresponding execution capability.</p>
 */
public final class AiInputUnitEstimator {

    public static final String VERSION =
            "UTF8_STRUCTURAL_UNITS_V2";

    public static final long MESSAGE_OVERHEAD_UNITS = 8L;

    private AiInputUnitEstimator() {
    }

    public static long estimateBaseRequest(
            String userMessage,
            List<AiMessage> history
    ) {
        Objects.requireNonNull(
                userMessage,
                "userMessage не должен быть null"
        );

        List<AiMessage> safeHistory = history == null
                ? List.of()
                : history;

        long total = estimateTextMessage(userMessage);

        for (AiMessage message : safeHistory) {
            Objects.requireNonNull(
                    message,
                    "history не должен содержать null"
            );
            total = Math.addExact(
                    total,
                    estimateTextMessage(message.content())
            );
        }

        return total;
    }

    public static long estimatePreparedRequest(
            AiChatRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        long total = estimateBaseRequest(
                request.userMessage(),
                request.history()
        );

        if (request.systemInstructions() != null) {
            total = addMessage(
                    total,
                    request.systemInstructions()
            );
        }

        if (request.developerInstructions() != null) {
            total = addMessage(
                    total,
                    request.developerInstructions()
            );
        }

        return total;
    }

    private static long addMessage(
            long current,
            String content
    ) {
        return Math.addExact(
                current,
                estimateTextMessage(content)
        );
    }

    private static long estimateTextMessage(
            String content
    ) {
        return Math.addExact(
                utf8Length(content),
                MESSAGE_OVERHEAD_UNITS
        );
    }

    public static long utf8Length(
            String value
    ) {
        return value == null
                ? 0L
                : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
