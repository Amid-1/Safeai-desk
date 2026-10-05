package ru.safeai.gateway.model.service;

import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.model.domain.ModelRouteDecision;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Typed immutable audit projection for one model-route governance decision.
 */
record ModelRouteDecisionAuditDetails(
        ModelRouteDecision decision,
        ModelRoutingCostPolicy.BudgetSnapshot budget
) implements AuditDetails {

    ModelRouteDecisionAuditDetails {
        Objects.requireNonNull(
                decision,
                "decision"
        );
        Objects.requireNonNull(
                budget,
                "budget"
        );
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> details = new LinkedHashMap<>();

        details.put(
                "decisionId",
                decision.id()
        );
        details.put(
                "chatId",
                decision.chatId()
        );
        details.put(
                "clientRequestId",
                decision.clientRequestId()
        );
        details.put(
                "outcome",
                decision.outcome()
        );
        details.put(
                "reason",
                decision.reason()
        );
        details.put(
                "decisionIntegrityVersion",
                decision.decisionIntegrityVersion()
        );
        details.put(
                "decisionSha256",
                decision.decisionSha256()
        );

        putIfNonNull(
                details,
                "chatTurnId",
                decision.chatTurnId()
        );
        putIfNonNull(
                details,
                "modelKey",
                decision.selectedModelKey()
        );
        putIfNonNull(
                details,
                "provider",
                decision.selectedProvider()
        );
        putIfNonNull(
                details,
                "providerModel",
                decision.selectedProviderModelId()
        );
        putIfNonNull(
                details,
                "catalogVersion",
                decision.selectedCatalogVersion()
        );
        putIfNonNull(
                details,
                "policyVersion",
                decision.policyVersion()
        );

        /*
         * Keep the existing audit field and exact decimal-string semantics,
         * but avoid a one-call generic helper whose key can never vary.
         */
        BigDecimal estimatedMaxCostUsd =
                decision.estimatedMaxCostUsd();
        if (estimatedMaxCostUsd != null) {
            details.put(
                    "estimatedMaxCostUsd",
                    estimatedMaxCostUsd.toPlainString()
            );
        }

        details.put(
                "budgetExceeded",
                decision.budgetExceeded()
        );
        details.put(
                "monthlyCostKnown",
                decision.monthlyCostKnown()
        );
        details.put(
                "monthlyCommitmentState",
                budget.monthlyCommitmentState()
        );
        details.put(
                "monthlyUnknownCostCount",
                budget.monthlyUnknownCostCount()
        );

        BigDecimal actual =
                budget.monthlyActualCostUsd();
        BigDecimal reserved =
                budget.monthlyReservedCostUsd();

        if ((actual == null) != (reserved == null)) {
            throw new IllegalStateException(
                    "Budget snapshot actual/reserved components must be present together"
            );
        }

        if (actual != null) {
            BigDecimal committed =
                    Objects.requireNonNull(
                            budget.monthlyCommittedUsd(),
                            "monthlyCommittedUsd"
                    );

            details.put(
                    "monthlyActualCostUsd",
                    actual.toPlainString()
            );
            details.put(
                    "monthlyReservedCostUsd",
                    reserved.toPlainString()
            );
            details.put(
                    "monthlyCommittedUsd",
                    committed.toPlainString()
            );
        }

        return Map.copyOf(
                details
        );
    }

    private static void putIfNonNull(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(
                    key,
                    value
            );
        }
    }
}
