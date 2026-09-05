package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.MonthlyCostState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ModelRouteDecisionResponse(
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
        String estimatedMaxCostUsd,
        String monthlyBudgetUsd,
        String monthlySpentUsd,
        String monthlyProjectedUsd,
        boolean monthlyCostKnown,
        MonthlyCostState monthlyCostState,
        BudgetEnforcement budgetEnforcement,
        boolean budgetExceeded,
        boolean pricingComplete,
        ModelRouteOutcome outcome,
        ModelRouteReason reason,
        short decisionIntegrityVersion,
        String decisionSha256,
        Instant createdAt
) {
    public static ModelRouteDecisionResponse from(
            ModelRouteDecision decision
    ) {
        return new ModelRouteDecisionResponse(
                decision.id(),
                decision.organizationId(),
                decision.userId(),
                decision.chatId(),
                decision.chatTurnId(),
                decision.clientRequestId(),
                decision.requestContentHash(),
                decision.requestedModelKey(),
                decision.selectedCatalogEntryId(),
                decision.selectedCatalogVersion(),
                decision.selectedModelKey(),
                decision.selectedProvider(),
                decision.selectedProviderModelId(),
                decision.policyId(),
                decision.policyVersion(),
                decision.requiredCapabilities(),
                decision.inputAccountingVersion(),
                decision.additionalInputUnitUpperBound(),
                decision.estimatedInputTokens(),
                decision.estimatedOutputTokens(),
                decimal(decision.estimatedMaxCostUsd()),
                decimal(decision.monthlyBudgetUsd()),
                decimal(decision.monthlySpentUsd()),
                decimal(decision.monthlyProjectedUsd()),
                decision.monthlyCostKnown(),
                decision.monthlyCostState(),
                decision.budgetEnforcement(),
                decision.budgetExceeded(),
                decision.pricingComplete(),
                decision.outcome(),
                decision.reason(),
                decision.decisionIntegrityVersion(),
                decision.decisionSha256(),
                decision.createdAt()
        );
    }

    private static String decimal(
            BigDecimal value
    ) {
        return value == null
                ? null
                : value.toPlainString();
    }
}
