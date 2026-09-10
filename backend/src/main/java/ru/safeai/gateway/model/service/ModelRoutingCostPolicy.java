package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

final class ModelRoutingCostPolicy {

    private static final BigDecimal ONE_MILLION =
            new BigDecimal("1000000");
    private static final int MONEY_SCALE = 12;

    private final ModelRouteDecisionRepository decisionRepository;

    ModelRoutingCostPolicy(
            ModelRouteDecisionRepository decisionRepository
    ) {
        this.decisionRepository = Objects.requireNonNull(
                decisionRepository,
                "decisionRepository не должен быть null"
        );
    }

    BudgetSnapshot evaluateBudget(
            UUID organizationId,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            PricingEstimate currentPricing,
            Instant now
    ) {
        return evaluateBudget(
                loadBudgetContext(
                        organizationId,
                        policy,
                        policyEnabled,
                        now
                ),
                policy,
                currentPricing
        );
    }

    BudgetContext loadBudgetContext(
            UUID organizationId,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            Instant now
    ) {
        return loadBudgetContext(
                organizationId,
                policy,
                policyEnabled,
                now,
                true
        );
    }

    /**
     * Read-only snapshot for administrative preview. Preview must never acquire
     * the live routing advisory lock and therefore cannot block data-plane work.
     */
    BudgetContext loadBudgetContextForPreview(
            UUID organizationId,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            Instant now
    ) {
        return loadBudgetContext(
                organizationId,
                policy,
                policyEnabled,
                now,
                false
        );
    }

    private BudgetContext loadBudgetContext(
            UUID organizationId,
            OrganizationModelPolicy policy,
            boolean policyEnabled,
            Instant now,
            boolean lockForRouting
    ) {
        if (!policyEnabled) {
            return BudgetContext.none();
        }

        Objects.requireNonNull(
                policy,
                "policy не должен быть null при policyEnabled=true"
        );

        if (policy.monthlyBudgetUsd() == null) {
            return BudgetContext.none();
        }

        if (lockForRouting) {
            decisionRepository.lockOrganizationBudget(organizationId);
        }

        YearMonth month =
                YearMonth.from(now.atZone(ZoneOffset.UTC));

        Instant periodStart =
                month.atDay(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();

        Instant periodEnd =
                month.plusMonths(1)
                        .atDay(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();

        ModelRouteDecisionRepository.MonthlyCostSnapshot commitment =
                decisionRepository.loadCommittedMonthlyCostSnapshot(
                        organizationId,
                        periodStart,
                        periodEnd
                );

        BigDecimal spent = commitment.committedCostUsd();
        boolean valid = !ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(spent);

        return new BudgetContext(
                true,
                valid,
                spent,
                commitment.unknownCommittedCostCount()
        );
    }

    BudgetSnapshot evaluateBudget(
            BudgetContext context,
            OrganizationModelPolicy policy,
            PricingEstimate currentPricing
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(
                currentPricing,
                "currentPricing не должен быть null"
        );

        if (!context.evaluated()) {
            return BudgetSnapshot.none();
        }

        Objects.requireNonNull(
                policy,
                "policy не должен быть null при budget evaluation"
        );

        if (!context.valid()) {
            return unverifiableBudget(policy);
        }

        BigDecimal spent = context.committedCostUsd();

        boolean currentCostKnown =
                currentPricing.complete()
                        && currentPricing.cost() != null;

        boolean costKnown =
                context.unknownCommittedCostCount() == 0L
                        && currentCostKnown;

        BigDecimal knownLowerBound =
                currentCostKnown
                        ? spent.add(currentPricing.cost())
                        : spent;

        if (ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(
                        knownLowerBound
                )) {
            return unverifiableBudget(policy);
        }

        BigDecimal projected =
                costKnown
                        ? knownLowerBound
                        : null;

        boolean provenExceeded =
                knownLowerBound.compareTo(
                        policy.monthlyBudgetUsd()
                ) > 0;

        if (policy.budgetEnforcement() == BudgetEnforcement.HARD
                && provenExceeded) {
            return new BudgetSnapshot(
                    policy.monthlyBudgetUsd(),
                    spent,
                    projected,
                    costKnown,
                    true,
                    ModelRouteReason.MONTHLY_BUDGET_EXCEEDED
            );
        }

        if (policy.budgetEnforcement() == BudgetEnforcement.HARD
                && !costKnown) {
            return new BudgetSnapshot(
                    policy.monthlyBudgetUsd(),
                    spent,
                    null,
                    false,
                    false,
                    ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
            );
        }

        return new BudgetSnapshot(
                policy.monthlyBudgetUsd(),
                spent,
                projected,
                costKnown,
                provenExceeded,
                null
        );
    }

    private static BudgetSnapshot unverifiableBudget(
            OrganizationModelPolicy policy
    ) {
        ModelRouteReason denialReason =
                policy.budgetEnforcement() == BudgetEnforcement.HARD
                        ? ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
                        : null;

        return new BudgetSnapshot(
                policy.monthlyBudgetUsd(),
                null,
                null,
                false,
                false,
                denialReason
        );
    }

    long effectiveInputLimit(
            ModelCatalogEntry entry,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            boolean policyEnabled
    ) {
        long limit = runtime.maxInputTokens();

        if (entry != null) {
            limit = Math.min(limit, entry.maxInputTokens());
        }

        if (policyEnabled && policy.maxInputTokens() != null) {
            limit = Math.min(limit, policy.maxInputTokens());
        }

        return limit;
    }

    long effectiveOutputLimit(
            ModelCatalogEntry entry,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            boolean policyEnabled
    ) {
        long limit = runtime.maxOutputTokens();

        if (entry != null) {
            limit = Math.min(limit, entry.maxOutputTokens());
        }

        if (policyEnabled && policy.maxOutputTokens() != null) {
            limit = Math.min(limit, policy.maxOutputTokens());
        }

        return limit;
    }

    /**
     * Historical DB/API field names still say "tokens"; V3 provenance records
     * the exact accounting version so these values remain explainable.
     */
    long estimateInputTokens(
            ModelRouteRequest request
    ) {
        long base =
                AiInputUnitEstimator.estimateBaseRequest(
                        request.userMessage(),
                        request.history()
                );

        return Math.addExact(
                base,
                request.additionalInputUnitUpperBound()
        );
    }

    PricingEstimate estimateCost(
            ModelCatalogEntry entry,
            RuntimeModelStatusResponse runtime,
            long inputUnits,
            long outputTokens
    ) {
        if (entry != null) {
            if (entry.pricingStatus() == ModelPricingStatus.FREE) {
                return new PricingEstimate(
                        BigDecimal.ZERO,
                        entry.pricingComplete()
                );
            }

            if (entry.inputUsdPer1mTokens() == null
                    || entry.outputUsdPer1mTokens() == null) {
                return new PricingEstimate(
                        null,
                        entry.pricingComplete()
                );
            }

            BigDecimal estimatedCost =
                    calculateWorstCaseCost(
                            inputUnits,
                            outputTokens,
                            entry.inputUsdPer1mTokens(),
                            entry.outputUsdPer1mTokens()
                    );

            if (ModelControlPlaneNumericValidation
                    .violatesNonNegativeNumeric30Scale12(
                            estimatedCost
                    )) {
                return new PricingEstimate(null, false);
            }

            return new PricingEstimate(
                    estimatedCost,
                    entry.pricingComplete()
            );
        }

        if ("FREE".equals(runtime.pricingStatus())) {
            return new PricingEstimate(BigDecimal.ZERO, true);
        }

        if (runtime.inputUsdPer1mTokens() == null
                || runtime.outputUsdPer1mTokens() == null) {
            return new PricingEstimate(null, false);
        }

        BigDecimal estimatedCost =
                calculateWorstCaseCost(
                        inputUnits,
                        outputTokens,
                        runtime.inputUsdPer1mTokens(),
                        runtime.outputUsdPer1mTokens()
                );

        if (ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(
                        estimatedCost
                )) {
            return new PricingEstimate(null, false);
        }

        // Legacy flat runtime pricing cannot prove all billing dimensions.
        return new PricingEstimate(estimatedCost, false);
    }

    private static BigDecimal calculateWorstCaseCost(
            long inputUnits,
            long outputTokens,
            BigDecimal inputRate,
            BigDecimal outputRate
    ) {
        BigDecimal inputCost =
                BigDecimal.valueOf(inputUnits)
                        .multiply(inputRate)
                        .divide(
                                ONE_MILLION,
                                MONEY_SCALE,
                                RoundingMode.HALF_UP
                        );

        BigDecimal outputCost =
                BigDecimal.valueOf(outputTokens)
                        .multiply(outputRate)
                        .divide(
                                ONE_MILLION,
                                MONEY_SCALE,
                                RoundingMode.HALF_UP
                        );

        return inputCost.add(outputCost)
                .setScale(
                        MONEY_SCALE,
                        RoundingMode.HALF_UP
                );
    }

    record BudgetContext(
            boolean evaluated,
            boolean valid,
            BigDecimal committedCostUsd,
            long unknownCommittedCostCount
    ) {
        static BudgetContext none() {
            return new BudgetContext(
                    false,
                    true,
                    BigDecimal.ZERO,
                    0L
            );
        }
    }

    record PricingEstimate(
            BigDecimal cost,
            boolean complete
    ) {
    }

    record BudgetSnapshot(
            BigDecimal monthlyBudgetUsd,
            BigDecimal monthlySpentUsd,
            BigDecimal monthlyProjectedUsd,
            boolean monthlyCostKnown,
            boolean exceeded,
            ModelRouteReason denialReason
    ) {
        static BudgetSnapshot none() {
            return new BudgetSnapshot(
                    null,
                    null,
                    null,
                    false,
                    false,
                    null
            );
        }
    }
}
