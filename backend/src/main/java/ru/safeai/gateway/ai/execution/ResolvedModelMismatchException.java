package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;

/**
 * A provider call completed, but its model identity violated the approved
 * execution target. The outcome is known and therefore never retryable or
 * ambiguous; the physical success evidence remains billable and durable.
 */
public final class ResolvedModelMismatchException
        extends AiProviderException {

    public ResolvedModelMismatchException(
            String provider,
            String requested,
            String actual,
            String providerRequestId
    ) {
        super(
                provider,
                requested,
                null,
                providerRequestId,
                "resolved_model_mismatch",
                AiProviderErrorType.PROTOCOL_ERROR,
                false,
                false,
                null,
                "Provider resolved model is not approved: requested="
                        + requested
                        + ", actual="
                        + actual,
                null
        );
    }
}
