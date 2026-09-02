package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.Objects;
import java.util.UUID;

/**
 * Final fail-closed guard between prompt/RAG materialization and provider I/O.
 */
public final class ModelRouteExecutionGuard {

    private ModelRouteExecutionGuard() {
    }

    public static void assertWithinReservedInputEnvelope(
            UUID decisionId,
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest
    ) {
        Objects.requireNonNull(
                decisionId,
                "decisionId не должен быть null"
        );

        assertWithinReservedInputEnvelopeInternal(
                decisionId,
                reservedRequest,
                preparedRequest
        );
    }

    /**
     * Compatibility entry point for direct/legacy tests that do not have a
     * model-route decision id.
     */
    public static void assertWithinReservedInputEnvelope(
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest
    ) {
        assertWithinReservedInputEnvelopeInternal(
                null,
                reservedRequest,
                preparedRequest
        );
    }

    private static void assertWithinReservedInputEnvelopeInternal(
            UUID decisionId,
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest
    ) {
        Objects.requireNonNull(
                reservedRequest,
                "reservedRequest не должен быть null"
        );
        Objects.requireNonNull(
                preparedRequest,
                "preparedRequest не должен быть null"
        );

        Long reserved =
                reservedRequest.reservedInputTokens();

        if (reserved == null) {
            return;
        }

        if (!Objects.equals(
                preparedRequest.reservedInputTokens(),
                reserved
        )) {
            throw new IllegalStateException(
                    "RAG/context transformation changed reservedInputTokens"
            );
        }

        if (!Objects.equals(
                preparedRequest.maxOutputTokens(),
                reservedRequest.maxOutputTokens()
        )) {
            throw new IllegalStateException(
                    "RAG/context transformation changed maxOutputTokens"
            );
        }

        long prepared =
                ModelInputTokenEstimator.estimatePreparedRequest(
                        preparedRequest
                );

        if (prepared > reserved) {
            throw new ModelRouteEnvelopeExceededException(
                    decisionId,
                    reserved,
                    prepared
            );
        }
    }
}
