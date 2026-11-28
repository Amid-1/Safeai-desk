// backend/src/main/java/ru/safeai/gateway/usage/dto/UsagePhysicalReportResponse.java
package ru.safeai.gateway.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;
import java.util.UUID;

/** Отдельный точный wire-контракт физического отчёта без округления JSON number. */
public record UsagePhysicalReportResponse(
        String from,
        String to,
        @JsonInclude(JsonInclude.Include.ALWAYS) UUID organizationId,
        String aggregationBasis,
        String planSelectionBasis,
        String currency,
        String physicalAttempts,
        String startedAttempts,
        String ambiguousAttempts,
        String knownExecutedAttempts,
        String knownNotExecutedAttempts,
        String knownRejectedAttempts,
        String costUnverifiableAttempts,
        String usageUnverifiableAttempts,
        String knownInputTokens,
        String knownOutputTokens,
        String knownCostUsd,
        boolean costComplete,
        boolean usageComplete,
        String plans,
        String costMatchedPlans,
        String costMismatchPlans,
        String unverifiablePlans,
        boolean fullyReconciled
) {
    public static UsagePhysicalReportResponse from(
            UsagePhysicalSpendResponse coverage,
            UsagePhysicalReconciliationResponse reconciliation
    ) {
        if (!coverage.from().equals(reconciliation.from())
                || !coverage.to().equals(reconciliation.to())
                || !Objects.equals(coverage.organizationId(), reconciliation.organizationId())) {
            throw new IllegalStateException("Physical report scope mismatch");
        }
        return new UsagePhysicalReportResponse(
                coverage.from().toString(), coverage.to().toString(), coverage.organizationId(),
                coverage.aggregationBasis(), reconciliation.planSelectionBasis(), coverage.currency(),
                Long.toString(coverage.physicalAttempts()), Long.toString(coverage.startedAttempts()),
                Long.toString(coverage.ambiguousAttempts()), Long.toString(coverage.knownExecutedAttempts()),
                Long.toString(coverage.knownNotExecutedAttempts()), Long.toString(coverage.knownRejectedAttempts()),
                Long.toString(coverage.costUnverifiableAttempts()), Long.toString(coverage.usageUnverifiableAttempts()),
                Long.toString(coverage.knownInputTokens()), Long.toString(coverage.knownOutputTokens()),
                coverage.knownCostUsd().toPlainString(), coverage.costComplete(), coverage.usageComplete(),
                Long.toString(reconciliation.plans()), Long.toString(reconciliation.costMatchedPlans()),
                Long.toString(reconciliation.costMismatchPlans()), Long.toString(reconciliation.unverifiablePlans()),
                reconciliation.fullyReconciled()
        );
    }
}
