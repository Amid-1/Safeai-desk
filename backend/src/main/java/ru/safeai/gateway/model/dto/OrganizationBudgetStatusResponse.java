package ru.safeai.gateway.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository.MonthlyCostSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Exact USD strings; a null remaining amount is not zero. */
public record OrganizationBudgetStatusResponse(
        UUID organizationId,
        int policyVersion,
        boolean policyEnabled,
        BudgetEnforcement enforcement,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodEnd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant asOf,
        @JsonInclude(JsonInclude.Include.ALWAYS) String monthlyBudgetUsd,
        String knownActualUsd,
        String reservedUsd,
        String knownCommittedUsd,
        long unknownCommittedCostCount,
        @JsonInclude(JsonInclude.Include.ALWAYS) String remainingUsd,
        String status
) {
    public static OrganizationBudgetStatusResponse from(
            OrganizationModelPolicyResponse policy, MonthlyCostSnapshot snapshot,
            Instant periodStart, Instant periodEnd, Instant asOf
    ) {
        BigDecimal budget = policy.monthlyBudgetUsd() == null
                ? null : new BigDecimal(policy.monthlyBudgetUsd());
        BigDecimal remaining = null;
        String status;
        if (!policy.enabled()) {
            status = policy.configured() ? "DISABLED" : "UNCONFIGURED";
        } else if (budget == null) {
            status = "NO_LIMIT";
        } else if (snapshot.unknownCommittedCostCount() > 0) {
            status = snapshot.committedCostUsd().compareTo(budget) > 0
                    ? "EXCEEDED_LOWER_BOUND" : "UNKNOWN";
        } else {
            BigDecimal difference = budget.subtract(snapshot.committedCostUsd());
            remaining = difference.max(BigDecimal.ZERO);
            status = difference.signum() < 0 ? "EXCEEDED" : "KNOWN";
        }
        return new OrganizationBudgetStatusResponse(
                policy.organizationId(), policy.version(), policy.enabled(), policy.budgetEnforcement(),
                periodStart, periodEnd, asOf, policy.monthlyBudgetUsd(),
                snapshot.actualCostUsd().toPlainString(), snapshot.reservedCostUsd().toPlainString(),
                snapshot.committedCostUsd().toPlainString(), snapshot.unknownCommittedCostCount(),
                remaining == null ? null : remaining.toPlainString(), status);
    }
}
