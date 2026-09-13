package ru.safeai.gateway.model.exception;

import java.io.Serial;
import java.util.UUID;

/**
 * Raised before provider I/O when the fully materialized AI request exceeds
 * the input-token envelope approved by model governance.
 *
 * <p>The structured values are intentionally retained separately from the
 * message so observability code can consume them without parsing text.</p>
 */
public final class ModelRouteEnvelopeExceededException
        extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID decisionId;
    private final long reservedInputTokens;
    private final long actualEstimatedInputTokens;

    public ModelRouteEnvelopeExceededException(
            UUID decisionId,
            long reservedInputTokens,
            long actualEstimatedInputTokens
    ) {
        super(
                "Prepared AI request exceeds reserved model-route input envelope: "
                        + "decisionId=" + decisionId
                        + ", reserved=" + reservedInputTokens
                        + ", prepared=" + actualEstimatedInputTokens
        );

        if (reservedInputTokens < 0L) {
            throw new IllegalArgumentException(
                    "reservedInputTokens не может быть отрицательным"
            );
        }

        if (actualEstimatedInputTokens <= reservedInputTokens) {
            throw new IllegalArgumentException(
                    "actualEstimatedInputTokens должен превышать "
                            + "reservedInputTokens"
            );
        }

        this.decisionId = decisionId;
        this.reservedInputTokens = reservedInputTokens;
        this.actualEstimatedInputTokens = actualEstimatedInputTokens;
    }

    /**
     * Persisted model-route decision. Null is allowed only for compatibility
     * callers that execute the guard without ChatTurn governance context.
     */
    public UUID decisionId() {
        return decisionId;
    }

    public long reservedInputTokens() {
        return reservedInputTokens;
    }

    public long actualEstimatedInputTokens() {
        return actualEstimatedInputTokens;
    }
}
