package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.model.domain.ModelRouteRequest;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Conservative tokenizer-independent upper estimator used by governance.
 *
 * <p>UTF-8 byte length is intentionally used as a token upper estimate for
 * ordinary text: one Unicode code point may occupy multiple bytes, so this is
 * conservative compared with practical provider tokenizers. The same
 * representation is used both before reservation and in the post-RAG guard.</p>
 */
final class ModelInputTokenEstimator {

    private static final long MESSAGE_OVERHEAD_TOKENS = 8L;

    private ModelInputTokenEstimator() {
    }

    static long estimateRouteRequest(
            ModelRouteRequest request
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        long total = Math.addExact(
                utf8Length(request.userMessage()),
                MESSAGE_OVERHEAD_TOKENS
        );

        for (AiMessage message : request.history()) {
            total = Math.addExact(
                    total,
                    Math.addExact(
                            utf8Length(message.content()),
                            MESSAGE_OVERHEAD_TOKENS
                    )
            );
        }

        return Math.addExact(
                total,
                request.additionalInputTokenUpperBound()
        );
    }

    static long estimatePreparedRequest(
            AiChatRequest request
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        long total = Math.addExact(
                utf8Length(request.userMessage()),
                MESSAGE_OVERHEAD_TOKENS
        );

        if (request.systemInstructions() != null) {
            total = Math.addExact(
                    total,
                    Math.addExact(
                            utf8Length(request.systemInstructions()),
                            MESSAGE_OVERHEAD_TOKENS
                    )
            );
        }

        if (request.developerInstructions() != null) {
            total = Math.addExact(
                    total,
                    Math.addExact(
                            utf8Length(request.developerInstructions()),
                            MESSAGE_OVERHEAD_TOKENS
                    )
            );
        }

        for (AiMessage message : request.history()) {
            total = Math.addExact(
                    total,
                    Math.addExact(
                            utf8Length(message.content()),
                            MESSAGE_OVERHEAD_TOKENS
                    )
            );
        }

        return total;
    }

    private static long utf8Length(
            String value
    ) {
        return value == null
                ? 0L
                : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
