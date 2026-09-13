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
 * <p>Integrity version {@code 1} is retained for persisted V45 evidence.
 * New V46 decisions are created and sealed as integrity version {@code 2}.</p>
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

        if (decisionIntegrityVersion != 1
                && decisionIntegrityVersion != 2) {
            throw new IllegalArgumentException(
                    "decisionIntegrityVersion должен быть 1 или 2"
            );
        }
    }

    /**
     * Monthly budget state exposed to audit/admin read models.
     *
     * <p>Budget enforcement mode can exist independently of an actual monthly
     * budget. Therefore absence of {@code monthlyBudgetUsd} means that monthly
     * budget evaluation was not applicable for this decision.</p>
     */
    public MonthlyCostState monthlyCostState() {
        if (monthlyBudgetUsd == null) {
            return MonthlyCostState.NOT_EVALUATED;
        }

        return monthlyCostKnown
                ? MonthlyCostState.KNOWN
                : MonthlyCostState.UNKNOWN;
    }
}
