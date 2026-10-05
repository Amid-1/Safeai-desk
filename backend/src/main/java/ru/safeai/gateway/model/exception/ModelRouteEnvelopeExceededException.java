package ru.safeai.gateway.model.exception;

import java.io.Serial;
import java.util.Objects;
import java.util.UUID;

/**
 * Raised before provider I/O when the fully materialized AI request exceeds
 * the input-unit envelope approved by Model Control Plane governance.
 *
 * <p>This is a deterministic pre-provider rejection: after it is raised,
 * no physical provider request may be started for the rejected execution.</p>
 *
 * <p>Input accounting is deliberately expressed in versioned input units,
 * not provider-specific tokenizer tokens. Structured values are retained
 * separately so orchestration, audit and observability code never need
 * to parse the exception message.</p>
 */
public final class ModelRouteEnvelopeExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID decisionId;
    private final long reservedInputUnits;
    private final long actualEstimatedInputUnits;

    public ModelRouteEnvelopeExceededException(
            UUID decisionId,
            long reservedInputUnits,
            long actualEstimatedInputUnits
    ) {
        super(message(
                decisionId,
                reservedInputUnits,
                actualEstimatedInputUnits
        ));

        this.decisionId = Objects.requireNonNull(
                decisionId,
                "decisionId не должен быть null"
        );
        this.reservedInputUnits = reservedInputUnits;
        this.actualEstimatedInputUnits = actualEstimatedInputUnits;
    }

    /**
     * Immutable persisted model-route decision that approved the execution
     * envelope.
     */
    public UUID decisionId() {
        return decisionId;
    }

    /**
     * Maximum number of versioned input-accounting units approved by governance.
     */
    public long reservedInputUnits() {
        return reservedInputUnits;
    }

    /**
     * Number of input-accounting units required by the fully materialized request.
     */
    public long actualEstimatedInputUnits() {
        return actualEstimatedInputUnits;
    }

    private static String message(
            UUID decisionId,
            long reservedInputUnits,
            long actualEstimatedInputUnits
    ) {
        Objects.requireNonNull(
                decisionId,
                "decisionId не должен быть null"
        );

        if (reservedInputUnits < 0L) {
            throw new IllegalArgumentException(
                    "reservedInputUnits не может быть отрицательным"
            );
        }

        if (actualEstimatedInputUnits < 0L) {
            throw new IllegalArgumentException(
                    "actualEstimatedInputUnits не может быть отрицательным"
            );
        }

        if (actualEstimatedInputUnits <= reservedInputUnits) {
            throw new IllegalArgumentException(
                    "actualEstimatedInputUnits должен превышать reservedInputUnits"
            );
        }

        return "Prepared AI request exceeds reserved model-route input envelope: "
                + "decisionId=" + decisionId
                + ", reservedInputUnits=" + reservedInputUnits
                + ", actualEstimatedInputUnits=" + actualEstimatedInputUnits;
    }
}
