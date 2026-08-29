package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable V45 model-routing evidence.
 *
 * <p>{@code pricingComplete} is primitive because the V45 database contract is
 * {@code NOT NULL}. Nullable pricing completeness would permit an in-memory
 * state that cannot be persisted and cannot exist when read back from V45.</p>
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
    }
}
