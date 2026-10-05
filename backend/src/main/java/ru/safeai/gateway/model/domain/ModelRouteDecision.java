package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable model-routing governance evidence.
 *
 * <p>Integrity versions 1 and 2 remain readable for historical V45/V46
 * evidence. New decisions use version 3 with explicit input-accounting
 * provenance; V64 adds a separate immutable budget-breakdown receipt without
 * changing the V3 canonical hash.</p>
 */
public record ModelRouteDecision(
        UUID id,
        UUID organizationId,
        UUID userId,
        UUID chatId,
        UUID chatTurnId,
        UUID clientRequestId,
        String requestContentHash,
        String requestedModelKey,
        UUID selectedCatalogEntryId,
        Integer selectedCatalogVersion,
        String selectedModelKey,
        String selectedProvider,
        String selectedProviderModelId,
        UUID policyId,
        Integer policyVersion,
        Set<ModelCapability> requiredCapabilities,
        String inputAccountingVersion,
        Long additionalInputUnitUpperBound,
        Long estimatedInputTokens,
        Long estimatedOutputTokens,
        BigDecimal estimatedMaxCostUsd,
        BigDecimal monthlyBudgetUsd,
        BigDecimal monthlySpentUsd,
        BigDecimal monthlyProjectedUsd,
        boolean monthlyCostKnown,
        BudgetEnforcement budgetEnforcement,
        boolean budgetExceeded,
        boolean pricingComplete,
        ModelRouteOutcome outcome,
        ModelRouteReason reason,
        short decisionIntegrityVersion,
        String decisionSha256,
        Instant createdAt
) {

    public ModelRouteDecision {
        if (requiredCapabilities == null
                || requiredCapabilities.isEmpty()) {
            requiredCapabilities = Set.of();
        } else {
            EnumSet<ModelCapability> copy =
                    EnumSet.noneOf(ModelCapability.class);

            copy.addAll(requiredCapabilities);

            requiredCapabilities =
                    Collections.unmodifiableSet(copy);
        }

        if (decisionIntegrityVersion < 1
                || decisionIntegrityVersion > 3) {
            throw new IllegalArgumentException(
                    "decisionIntegrityVersion должен быть 1, 2 или 3"
            );
        }

        if (decisionIntegrityVersion < 3) {
            if (inputAccountingVersion != null
                    || additionalInputUnitUpperBound != null) {
                throw new IllegalArgumentException(
                        "V1/V2 evidence не должно содержать V3 accounting provenance"
                );
            }
        } else {
            if (inputAccountingVersion == null
                    || inputAccountingVersion.isBlank()
                    || !inputAccountingVersion.equals(inputAccountingVersion.trim())
                    || inputAccountingVersion.length() > 64
                    || inputAccountingVersion.codePoints()
                    .anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "V3 inputAccountingVersion должен быть непустым безопасным идентификатором"
                );
            }
            if (additionalInputUnitUpperBound == null
                    || additionalInputUnitUpperBound < 0L) {
                throw new IllegalArgumentException(
                        "V3 additionalInputUnitUpperBound обязателен и не может быть отрицательным"
                );
            }
        }
    }

    /**
     * Backward-compatible exactness state exposed by the pre-V64 API.
     */
    public MonthlyCostState monthlyCostState() {
        if (monthlyBudgetUsd == null) {
            return MonthlyCostState.NOT_EVALUATED;
        }
        return monthlyCostKnown ? MonthlyCostState.KNOWN : MonthlyCostState.UNKNOWN;
    }

    /**
     * Precise V64 commitment/budget state. Exactness and proven overrun are
     * independent; unknown liability can coexist with a lower bound that has
     * already exceeded the configured budget.
     */
    public MonthlyCommitmentState monthlyCommitmentState() {
        if (monthlyBudgetUsd == null) {
            return MonthlyCommitmentState.NOT_EVALUATED;
        }
        if (monthlyCostKnown) {
            return budgetExceeded
                    ? MonthlyCommitmentState.KNOWN_EXCEEDED
                    : MonthlyCommitmentState.KNOWN_WITHIN;
        }
        if (budgetExceeded) {
            return MonthlyCommitmentState.UNKNOWN_EXCEEDED_LOWER_BOUND;
        }
        if (reason == ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
                || monthlySpentUsd == null) {
            return MonthlyCommitmentState.UNVERIFIABLE;
        }
        return MonthlyCommitmentState.UNKNOWN_WITHIN_LOWER_BOUND;
    }

    /**
     * Semantic alias for the historical {@code monthly_spent_usd} field. The
     * persisted column/component name is retained for V1-V3 hash/schema
     * compatibility, but its value is a commitment snapshot, not pure actual
     * provider spend.
     */
    public BigDecimal monthlyCommittedUsd() {
        return monthlySpentUsd;
    }

}
