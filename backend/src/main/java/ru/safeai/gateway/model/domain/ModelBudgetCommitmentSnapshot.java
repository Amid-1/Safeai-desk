package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable V64 budget evidence captured at route-decision time.
 *
 * <p>Actual and reserved values describe commitments that existed before the
 * current logical request was added to the projection. They are nullable only
 * when an exact monetary breakdown itself is not representable. Unknown amount
 * is never converted to zero; {@code commitmentState} carries the semantic truth.</p>
 */
public record ModelBudgetCommitmentSnapshot(
        BigDecimal actualCostUsd,
        BigDecimal reservedCostUsd,
        long unknownCostCount,
        MonthlyCommitmentState commitmentState
) {
    public ModelBudgetCommitmentSnapshot {
        Objects.requireNonNull(commitmentState, "commitmentState");
        if (commitmentState == MonthlyCommitmentState.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                    "A persisted budget snapshot cannot be NOT_EVALUATED"
            );
        }
        if ((actualCostUsd == null) != (reservedCostUsd == null)) {
            throw new IllegalArgumentException(
                    "actualCostUsd and reservedCostUsd must be present together"
            );
        }
        if (actualCostUsd != null
                && (actualCostUsd.signum() < 0 || reservedCostUsd.signum() < 0)) {
            throw new IllegalArgumentException(
                    "Cost commitment components must be non-negative"
            );
        }
        if (unknownCostCount < 0L) {
            throw new IllegalArgumentException(
                    "unknownCostCount must be non-negative"
            );
        }

        switch (commitmentState) {
            case KNOWN_WITHIN, KNOWN_EXCEEDED -> {
                if (actualCostUsd == null || unknownCostCount != 0L) {
                    throw new IllegalArgumentException(
                            "Known budget state requires exact actual/reserved values and zero unknown count"
                    );
                }
            }
            case UNKNOWN_WITHIN_LOWER_BOUND, UNKNOWN_EXCEEDED_LOWER_BOUND -> {
                if (actualCostUsd == null || unknownCostCount == 0L) {
                    throw new IllegalArgumentException(
                            "Unknown lower-bound state requires exact known components and unknown liability"
                    );
                }
            }
            case UNVERIFIABLE -> {
                // Components may still be known; the failure can be a projection/
                // numeric representability problem rather than an unknown provider amount.
            }
            case NOT_EVALUATED -> throw new IllegalStateException("unreachable");
        }
    }

    public BigDecimal committedCostUsd() {
        return actualCostUsd == null
                ? null
                : actualCostUsd.add(reservedCostUsd);
    }
}
