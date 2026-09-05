package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.Objects;
import java.util.UUID;

/**
 * Final fail-closed boundary between prompt/RAG materialization and provider I/O.
 */
public final class ModelRouteExecutionGuard {

    private ModelRouteExecutionGuard() {
    }

    /**
     * The only production entry point. A governed request is always bound to
     * an immutable model-route decision.
     */
    public static void assertWithinReservedInputEnvelope(
            UUID decisionId,
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest
    ) {
        Objects.requireNonNull(
                decisionId,
                "decisionId не должен быть null"
        );
        Objects.requireNonNull(
                reservedRequest,
                "reservedRequest не должен быть null"
        );
        Objects.requireNonNull(
                preparedRequest,
                "preparedRequest не должен быть null"
        );

        requireBaseRequestIdentity(
                reservedRequest,
                preparedRequest
        );

        long reserved = requireReservedInputUnits(
                decisionId,
                reservedRequest
        );

        requireExecutionEnvelopePreserved(
                reservedRequest,
                preparedRequest,
                reserved
        );

        long prepared =
                AiInputUnitEstimator.estimatePreparedRequest(
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

    private static long requireReservedInputUnits(
            UUID decisionId,
            AiChatRequest reservedRequest
    ) {
        Long reserved = reservedRequest.reservedInputUnits();

        if (reserved == null) {
            throw new IllegalStateException(
                    "Governed AI request has no reservedInputUnits: "
                            + decisionId
            );
        }

        return reserved;
    }

    private static void requireExecutionEnvelopePreserved(
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest,
            long reservedInputUnits
    ) {
        if (!Objects.equals(
                preparedRequest.reservedInputUnits(),
                reservedInputUnits
        )) {
            throw new IllegalStateException(
                    "RAG/context transformation changed reserved input envelope"
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
    }

    private static void requireBaseRequestIdentity(
            AiChatRequest reserved,
            AiChatRequest prepared
    ) {
        if (!Objects.equals(
                reserved.userId(),
                prepared.userId()
        )
                || !Objects.equals(
                reserved.organizationId(),
                prepared.organizationId()
        )
                || !Objects.equals(
                reserved.chatId(),
                prepared.chatId()
        )
                || !Objects.equals(
                reserved.providerOperationId(),
                prepared.providerOperationId()
        )
                || !Objects.equals(
                reserved.userMessage(),
                prepared.userMessage()
        )
                || !Objects.equals(
                reserved.history(),
                prepared.history()
        )) {
            throw new IllegalStateException(
                    "AI request transformation changed route-bound request identity"
            );
        }
    }
}
