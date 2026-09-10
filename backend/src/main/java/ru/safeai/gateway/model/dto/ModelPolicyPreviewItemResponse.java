package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;

import java.math.BigDecimal;

/** One effective catalog model evaluated against a draft organization policy. */
public record ModelPolicyPreviewItemResponse(
        String modelKey,
        int catalogVersion,
        String provider,
        String providerModelId,
        ModelLifecycle lifecycle,
        ModelRouteOutcome outcome,
        ModelRouteReason reason,
        long effectiveInputLimit,
        long effectiveOutputLimit,
        boolean pricingComplete,
        String estimatedMaxCostUsd,
        String monthlyBudgetUsd,
        String monthlyProjectedUsd,
        boolean monthlyCostKnown,
        boolean budgetExceeded
) {
    public static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
