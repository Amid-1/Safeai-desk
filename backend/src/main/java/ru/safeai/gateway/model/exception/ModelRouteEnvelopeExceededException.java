package ru.safeai.gateway.model.exception;

import java.io.Serial;
import java.util.Objects;
import java.util.UUID;

/**
 * Raised before provider I/O when the fully materialized AI request exceeds
 * the input-unit envelope approved by model governance.
 *
 * <p>This is a deterministic pre-provider failure. The provider has not been
 * invoked, therefore the corresponding ChatTurn must become FAILED rather
 * than AMBIGUOUS.</p>
 */
public final class ModelRouteEnvelopeExceededException
        extends RuntimeException {

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
        super(
                buildMessage(
                        decisionId,
                        reservedInputUnits,
                        actualEstimatedInputUnits
                )
        );

        this.decisionId =
                Objects.requireNonNull(
                        decisionId,
                        "decisionId не должен быть null"
                );

        if (reservedInputUnits < 0L) {
            throw new IllegalArgumentException(
                    "reservedInputUnits не может быть отрицательным"
            );
        }

        if (actualEstimatedInputUnits <= reservedInputUnits) {
            throw new IllegalArgumentException(
                    "actualEstimatedInputUnits должен превышать "
                            + "reservedInputUnits"
            );
        }

        this.reservedInputUnits =
                reservedInputUnits;

        this.actualEstimatedInputUnits =
                actualEstimatedInputUnits;
    }

    public UUID decisionId() {
        return decisionId;
    }

    public long reservedInputUnits() {
        return reservedInputUnits;
    }

    public long actualEstimatedInputUnits() {
        return actualEstimatedInputUnits;
    }

    private static String buildMessage(
            UUID decisionId,
            long reservedInputUnits,
            long actualEstimatedInputUnits
    ) {
        return "Prepared AI request exceeds reserved "
                + "model-route input envelope: "
                + "decisionId="
                + decisionId
                + ", reservedUnits="
                + reservedInputUnits
                + ", preparedUnits="
                + actualEstimatedInputUnits;
    }
}