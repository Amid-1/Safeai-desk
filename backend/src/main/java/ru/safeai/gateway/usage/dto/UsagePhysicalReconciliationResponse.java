package ru.safeai.gateway.usage.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reconciles full physical attempt history per selected execution plan with
 * the single persisted assistant message linked through chat_turns.
 */
public record UsagePhysicalReconciliationResponse(
        Instant from,
        Instant to,
        UUID organizationId,
        String planSelectionBasis,
        long plans,
        long costMatchedPlans,
        long costMismatchPlans,
        long unverifiablePlans,
        boolean fullyReconciled
) {
    public UsagePhysicalReconciliationResponse {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from должен быть раньше to");
        }
        if (!"PLANS_WITH_PHYSICAL_START_IN_RANGE".equals(planSelectionBasis)) {
            throw new IllegalArgumentException("Некорректная база reconciliation");
        }
        if (plans < 0 || costMatchedPlans < 0 || costMismatchPlans < 0
                || unverifiablePlans < 0
                || Math.addExact(Math.addExact(costMatchedPlans, costMismatchPlans),
                        unverifiablePlans) != plans) {
            throw new IllegalArgumentException("Неконсистентные reconciliation counters");
        }
        if (fullyReconciled != (plans > 0
                && costMismatchPlans == 0 && unverifiablePlans == 0)) {
            throw new IllegalArgumentException(
                    "fullyReconciled противоречит reconciliation counters");
        }
    }

    /** Preferred constructor: derive the flag from verified plan counters. */
    public UsagePhysicalReconciliationResponse(
            Instant from, Instant to, UUID organizationId,
            String planSelectionBasis, long plans,
            long costMatchedPlans, long costMismatchPlans, long unverifiablePlans
    ) {
        this(from, to, organizationId, planSelectionBasis, plans,
                costMatchedPlans, costMismatchPlans, unverifiablePlans,
                plans > 0 && costMismatchPlans == 0 && unverifiablePlans == 0);
    }
}
