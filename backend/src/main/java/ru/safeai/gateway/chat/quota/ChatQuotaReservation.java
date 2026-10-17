package ru.safeai.gateway.chat.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable quota reservation produced from the already selected model route.
 *
 * <p>The reservation is bound to the immutable route decision and must never
 * be replaced by a deployment-wide static estimate.</p>
 *
 * <p>{@code maxCostUsd} is a proven upper bound. When its source has more than
 * the database scale, it is rounded toward positive infinity rather than using
 * normal HALF_UP/HALF_EVEN rounding, because rounding an upper bound downward
 * would weaken a hard monetary quota.</p>
 */
@SuppressWarnings("unused")
public record ChatQuotaReservation(
        UUID modelRouteDecisionId,
        long inputUnits,
        long outputUnits,
        BigDecimal maxCostUsd,
        boolean costVerifiable
) {
    private static final int DATABASE_MONEY_PRECISION = 30;
    private static final int DATABASE_MONEY_SCALE = 12;

    private static final BigDecimal UNKNOWN_DATABASE_COST =
            BigDecimal.ZERO.setScale(
                    DATABASE_MONEY_SCALE,
                    RoundingMode.UNNECESSARY
            );

    public ChatQuotaReservation {
        Objects.requireNonNull(
                modelRouteDecisionId,
                "modelRouteDecisionId не должен быть null"
        );

        if (inputUnits < 0) {
            throw new IllegalArgumentException(
                    "inputUnits не может быть отрицательным"
            );
        }

        if (outputUnits < 0) {
            throw new IllegalArgumentException(
                    "outputUnits не может быть отрицательным"
            );
        }

        if (costVerifiable) {
            if (maxCostUsd == null) {
                throw new IllegalArgumentException(
                        "costVerifiable=true требует maxCostUsd"
                );
            }

            if (maxCostUsd.signum() < 0) {
                throw new IllegalArgumentException(
                        "maxCostUsd не может быть отрицательным"
                );
            }

            /*
             * PostgreSQL chat_quota_reservations.reserved_cost_usd is
             * numeric(30,12). Preserve the upper-bound invariant explicitly
             * before persistence.
             */
            maxCostUsd =
                    maxCostUsd.setScale(
                            DATABASE_MONEY_SCALE,
                            RoundingMode.CEILING
                    );

            if (maxCostUsd.precision()
                    > DATABASE_MONEY_PRECISION) {

                throw new IllegalArgumentException(
                        "maxCostUsd превышает numeric("
                                + DATABASE_MONEY_PRECISION
                                + ","
                                + DATABASE_MONEY_SCALE
                                + ")"
                );
            }
        } else if (maxCostUsd != null) {
            throw new IllegalArgumentException(
                    "Непроверяемая стоимость не должна выглядеть доказанной"
            );
        }
    }

    /**
     * Storage compatibility for the existing NOT NULL numeric(30,12) column.
     *
     * <p>Zero returned for an unverifiable reservation means UNKNOWN, not FREE.
     * Callers must persist/read {@code costVerifiable=false} together with it.</p>
     */
    public BigDecimal databaseReservedCostUsd() {
        return costVerifiable
                ? maxCostUsd
                : UNKNOWN_DATABASE_COST;
    }
}
