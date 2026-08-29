package ru.safeai.gateway.model.exception;

import lombok.Getter;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.model.domain.ModelRouteReason;

import java.io.Serial;
import java.util.Objects;
import java.util.UUID;

/**
 * Governance rejection persisted as immutable
 * model_route_decisions evidence.
 * <p>
 * ChatTurnReservationService intentionally commits
 * ModelRouteDeniedException via noRollbackFor so that
 * a deterministic DENIED route decision remains durable.
 */
@Getter
public class ModelRouteDeniedException
        extends ForbiddenOperationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID decisionId;
    private final ModelRouteReason reason;

    public ModelRouteDeniedException(
            UUID decisionId,
            ModelRouteReason reason,
            String publicMessage
    ) {
        super(
                Objects.requireNonNull(
                        publicMessage,
                        "publicMessage не должен быть null"
                )
        );

        this.decisionId =
                Objects.requireNonNull(
                        decisionId,
                        "decisionId не должен быть null"
                );

        this.reason =
                Objects.requireNonNull(
                        reason,
                        "reason не должен быть null"
                );
    }

}