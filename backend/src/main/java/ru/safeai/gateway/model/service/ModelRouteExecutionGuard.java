package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.Objects;

/**
 * Final fail-closed boundary between the DB-backed execution identity,
 * the immutable pre-RAG request and the fully materialized provider request.
 *
 * <p>This guard intentionally lives in the model.service package. The
 * execution identity verification methods and the model-side accounting
 * adapter are package-private implementation details and must not be exposed
 * merely to make this boundary callable from another package.</p>
 */
public final class ModelRouteExecutionGuard {

    private ModelRouteExecutionGuard() {
    }

    /**
     * Verifies that the fully materialized provider request is still the exact
     * governed request and remains inside the input envelope reserved by the
     * persisted Model Control Plane decision.
     *
     * <p>This method is expected to run after durable execution identity
     * verification and final request materialization, but before execution-plan
     * creation, physical attempt STARTED and provider I/O.</p>
     *
     * @param identity        DB-verified immutable execution identity
     * @param reservedRequest exact sealed pre-RAG request
     * @param preparedRequest final request that would be sent to the provider
     */
    public static void assertWithinReservedInputEnvelope(
            ModelRouteExecutionIdentity identity,
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest
    ) {
        Objects.requireNonNull(
                identity,
                "identity не должен быть null"
        );
        Objects.requireNonNull(
                reservedRequest,
                "reservedRequest не должен быть null"
        );
        Objects.requireNonNull(
                preparedRequest,
                "preparedRequest не должен быть null"
        );

        /*
         * Keep the identity verification package-private. The guard belongs to
         * this package precisely so these internal invariants do not have to be
         * exposed as part of the public service API.
         */
        identity.requireSameBase(
                reservedRequest
        );
        identity.requirePreparedBase(
                reservedRequest,
                preparedRequest
        );

        /*
         * Fail closed if persisted execution evidence references an accounting
         * contract this running binary does not understand.
         */
        if (!AiInputUnitEstimator.VERSION.equals(
                identity.inputAccountingVersion()
        )) {
            throw new IllegalStateException(
                    "Unknown input accounting version: "
                            + identity.inputAccountingVersion()
            );
        }

        /*
         * Use the single model-side adapter so route reservation and final
         * execution checks cannot silently drift to different accounting
         * implementations.
         */
        long preparedInputUnits =
                ModelInputTokenEstimator.estimatePreparedRequest(
                        preparedRequest
                );

        if (preparedInputUnits > identity.reservedInputUnits()) {
            throw new ModelRouteEnvelopeExceededException(
                    identity.decisionId(),
                    identity.reservedInputUnits(),
                    preparedInputUnits
            );
        }
    }
}
