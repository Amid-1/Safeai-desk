package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.model.domain.ModelRouteRequest;

import java.util.Objects;

/**
 * Single model-layer adapter for versioned deterministic input accounting.
 *
 * <p>No other model-control-plane class should call {@link AiInputUnitEstimator}
 * directly. Keeping reservation, execution and durable binding on one adapter
 * prevents silent accounting drift.</p>
 */
final class ModelInputTokenEstimator {

    private ModelInputTokenEstimator() {
    }

    static String version() {
        return AiInputUnitEstimator.VERSION;
    }

    static long estimateBaseRequest(AiChatRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");
        return AiInputUnitEstimator.estimateBaseRequest(
                request.userMessage(),
                request.history()
        );
    }

    static long estimateBaseRequest(ModelRouteRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");
        return AiInputUnitEstimator.estimateBaseRequest(
                request.userMessage(),
                request.history()
        );
    }

    static long estimateRouteRequest(ModelRouteRequest request) {
        return Math.addExact(
                estimateBaseRequest(request),
                request.additionalInputUnitUpperBound()
        );
    }

    static long estimatePreparedRequest(AiChatRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");
        return AiInputUnitEstimator.estimatePreparedRequest(request);
    }
}
