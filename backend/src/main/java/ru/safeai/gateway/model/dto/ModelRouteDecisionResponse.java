package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelBudgetCommitmentSnapshot;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.MonthlyCommitmentState;
import ru.safeai.gateway.model.domain.MonthlyCostState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable admin/audit projection of one route decision. */
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
        String monthlyActualCostUsd,
        String monthlyReservedCostUsd,
        String monthlyCommittedUsd,
        /* Historical compatibility alias; value equals monthlyCommittedUsd. */
        String monthlySpentUsd,
        Long monthlyUnknownCostCount,
        String monthlyProjectedUsd,
        boolean monthlyCostKnown,
        MonthlyCostState monthlyCostState,
        MonthlyCommitmentState monthlyCommitmentState,
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
            ModelRouteDecision decision,
            ModelBudgetCommitmentSnapshot commitment
    ) {
        BigDecimal actual = commitment == null ? null : commitment.actualCostUsd();
        BigDecimal reserved = commitment == null ? null : commitment.reservedCostUsd();
        BigDecimal committed = commitment == null
                ? decision.monthlyCommittedUsd()
                : commitment.committedCostUsd();
        Long unknownCount = commitment == null
                ? null
                : commitment.unknownCostCount();
        MonthlyCommitmentState commitmentState = commitment == null
                ? decision.monthlyCommitmentState()
                : commitment.commitmentState();

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
                decimal(actual),
                decimal(reserved),
                decimal(committed),
                decimal(committed),
                unknownCount,
                decimal(decision.monthlyProjectedUsd()),
                decision.monthlyCostKnown(),
                decision.monthlyCostState(),
                commitmentState,
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

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
