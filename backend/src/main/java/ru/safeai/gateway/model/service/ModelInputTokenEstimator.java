package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.model.domain.ModelRouteRequest;

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

    private ModelInputTokenEstimator() {
    }

    static long estimateRouteRequest(
            ModelRouteRequest request
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        return Math.addExact(
                AiInputUnitEstimator.estimateBaseRequest(
                        request.userMessage(),
                        request.history()
                ),
                request.additionalInputUnitUpperBound()
        );
    }

    static long estimatePreparedRequest(
            AiChatRequest request
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        return AiInputUnitEstimator.estimatePreparedRequest(request);
    }
}
