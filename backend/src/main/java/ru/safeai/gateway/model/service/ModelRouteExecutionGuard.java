package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.Objects;

/** Final identity + exact-reservation + physical input-envelope guard. */
public final class ModelRouteExecutionGuard {
    private ModelRouteExecutionGuard() { }

    public static void assertWithinReservedInputEnvelope(
            ModelRouteExecutionIdentity identity,
            AiChatRequest reserved,
            AiChatRequest prepared
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(prepared, "prepared");
        identity.requireSameBase(reserved);
        identity.requirePreparedBase(reserved, prepared);
        if (!AiInputUnitEstimator.VERSION.equals(identity.inputAccountingVersion())) {
            throw new IllegalStateException("Unknown input accounting version");
        }
        long estimate = AiInputUnitEstimator.estimatePreparedRequest(prepared);
        if (estimate > identity.reservedInputUnits()) {
            throw new ModelRouteEnvelopeExceededException(
                    identity.decisionId(), identity.reservedInputUnits(), estimate);
        }
    }
}
