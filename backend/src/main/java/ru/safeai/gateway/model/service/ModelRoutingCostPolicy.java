package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.*;
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
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int MONEY_SCALE = 12;
    private final ModelRouteDecisionRepository decisionRepository;

    ModelRoutingCostPolicy(ModelRouteDecisionRepository decisionRepository) {
        this.decisionRepository = Objects.requireNonNull(decisionRepository, "decisionRepository");
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
     * Read-only snapshot for administrative preview. A preview must not take
     * the data-plane budget lock or block a live routing decision.
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
        Objects.requireNonNull(policy, "policy не должен быть null при policyEnabled=true");

        if (policy.monthlyBudgetUsd() == null) {
            return BudgetContext.none();
        }

        if (lockForRouting) {
            decisionRepository.lockOrganizationBudget(organizationId);
        }

        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        Instant periodStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        ModelRouteDecisionRepository.MonthlyCostSnapshot commitment =
                decisionRepository.loadCommittedMonthlyCostSnapshot(
                        organizationId,
                        periodStart,
                        periodEnd
                );

        BigDecimal committed = commitment.committedCostUsd();
        boolean valid = !ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(committed)
                && !ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(commitment.actualCostUsd())
                && !ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(commitment.reservedCostUsd());

        return new BudgetContext(
                true,
                valid,
                commitment.actualCostUsd(),
                commitment.reservedCostUsd(),
                commitment.unknownCommittedCostCount()
        );
    }

    BudgetSnapshot evaluateBudget(
            BudgetContext context,
            OrganizationModelPolicy policy,
            PricingEstimate currentPricing
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(currentPricing, "currentPricing не должен быть null");

        if (!context.evaluated()) {
            return BudgetSnapshot.none();
        }
        Objects.requireNonNull(policy, "policy не должен быть null при budget evaluation");

        if (!context.valid()) {
            return unverifiableBudget(
                    policy,
                    context.actualCostUsd(),
                    context.reservedCostUsd(),
                    context.unknownCommittedCostCount()
            );
        }

        BigDecimal spent = context.committedCostUsd();

        boolean currentCostKnown =
                currentPricing.complete()
                        && !ModelControlPlaneNumericValidation
                                .violatesNonNegativeNumeric30Scale12(currentPricing.cost());

        boolean costKnown =
                context.unknownCommittedCostCount() == 0L
                        && currentCostKnown;

        if (!costKnown) {
            // V62 keeps exactness and overrun as independent facts. Unknown
            // liability does not erase a budget overrun that is already proven
            // by the known committed amount plus any known current estimate.
            boolean exceeded = knownCommitmentExceedsBudget(
                    spent,
                    currentPricing.complete() ? currentPricing.cost() : null,
                    policy.monthlyBudgetUsd()
            );
            ModelRouteReason denialReason = null;
            if (policy.budgetEnforcement() == BudgetEnforcement.HARD) {
                denialReason = exceeded
                        ? ModelRouteReason.MONTHLY_BUDGET_EXCEEDED
                        : ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE;
            }
            long unknownLiabilityCount = Math.addExact(
                    context.unknownCommittedCostCount(),
                    currentCostKnown ? 0L : 1L
            );
            MonthlyCommitmentState state = exceeded
                    ? MonthlyCommitmentState.UNKNOWN_EXCEEDED_LOWER_BOUND
                    : policy.budgetEnforcement() == BudgetEnforcement.HARD
                    ? MonthlyCommitmentState.UNVERIFIABLE
                    : MonthlyCommitmentState.UNKNOWN_WITHIN_LOWER_BOUND;

            return new BudgetSnapshot(
                    policy.monthlyBudgetUsd(),
                    context.actualCostUsd(),
                    context.reservedCostUsd(),
                    unknownLiabilityCount,
                    null,
                    false,
                    exceeded,
                    denialReason,
                    state
            );
        }
        BigDecimal projected = spent.add(currentPricing.cost());
        if (ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(projected)) {
            return unverifiableBudget(
                    policy,
                    context.actualCostUsd(),
                    context.reservedCostUsd(),
                    0L
            );
        }
        boolean exceeded = projected.compareTo(policy.monthlyBudgetUsd()) > 0;
        return new BudgetSnapshot(
                policy.monthlyBudgetUsd(),
                context.actualCostUsd(),
                context.reservedCostUsd(),
                0L,
                projected,
                true,
                exceeded,
                policy.budgetEnforcement() == BudgetEnforcement.HARD && exceeded
                        ? ModelRouteReason.MONTHLY_BUDGET_EXCEEDED : null,
                exceeded
                        ? MonthlyCommitmentState.KNOWN_EXCEEDED
                        : MonthlyCommitmentState.KNOWN_WITHIN
        );
    }


    private static boolean knownCommitmentExceedsBudget(
            BigDecimal committedBeforeRequest,
            BigDecimal currentKnownEstimate,
            BigDecimal monthlyBudgetUsd
    ) {
        Objects.requireNonNull(committedBeforeRequest, "committedBeforeRequest");
        Objects.requireNonNull(monthlyBudgetUsd, "monthlyBudgetUsd");

        if (committedBeforeRequest.compareTo(monthlyBudgetUsd) > 0) {
            return true;
        }
        if (currentKnownEstimate == null
                || ModelControlPlaneNumericValidation
                        .violatesNonNegativeNumeric30Scale12(currentKnownEstimate)) {
            return false;
        }

        // BigDecimal arithmetic itself is unbounded. Even if the sum no longer
        // fits numeric(30,12), it is certainly above any valid numeric(30,12)
        // budget because both components are non-negative.
        BigDecimal knownProjectedCommitment =
                committedBeforeRequest.add(currentKnownEstimate);
        return knownProjectedCommitment.compareTo(monthlyBudgetUsd) > 0;
    }

    private static BudgetSnapshot unverifiableBudget(
            OrganizationModelPolicy policy,
            BigDecimal actualCostUsd,
            BigDecimal reservedCostUsd,
            long unknownCostCount
    ) {
        ModelRouteReason denialReason =
                policy.budgetEnforcement() == BudgetEnforcement.HARD
                        ? ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
                        : null;

        return new BudgetSnapshot(
                policy.monthlyBudgetUsd(),
                actualCostUsd,
                reservedCostUsd,
                Math.max(unknownCostCount, 0L),
                null,
                false,
                false,
                denialReason,
                MonthlyCommitmentState.UNVERIFIABLE
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

    long estimateInputTokens(ModelRouteRequest request) {
        Objects.requireNonNull(request, "request");
        return ModelInputTokenEstimator.estimateRouteRequest(request);
    }

    PricingEstimate estimateCost(
            ModelCatalogEntry entry,
            RuntimeModelStatusResponse runtime,
            long inputTokens,
            long outputTokens
    ) {
        if (entry != null) {
            // V66: legacy, unknown and complex schemes are never a proven
            // reservation estimate for HARD monthly budget enforcement.
            if (!entry.pricingScheme().supportedForAutomaticSettlement()) {
                return new PricingEstimate(null, false);
            }
            if (entry.pricingStatus() == ModelPricingStatus.FREE) {
                return new PricingEstimate(BigDecimal.ZERO, entry.pricingComplete());
            }

            if (entry.pricingStatus() != ModelPricingStatus.CONFIGURED
                    || !entry.pricingComplete()) {
                return new PricingEstimate(null, false);
            }
            if (entry.inputUsdPer1mTokens() == null
                    || entry.outputUsdPer1mTokens() == null) {
                return new PricingEstimate(null, true);
            }

            // For a generic tariff the three supported input prices are
            // independent: any token may be billed at the most expensive
            // applicable rate. Do not assume cache-write <= ordinary input.
            BigDecimal worstInputRate = entry.inputUsdPer1mTokens();
            if (entry.cachedInputUsdPer1mTokens() != null) {
                worstInputRate = worstInputRate.max(entry.cachedInputUsdPer1mTokens());
            }
            if (entry.cacheWriteInputUsdPer1mTokens() != null) {
                worstInputRate = worstInputRate.max(entry.cacheWriteInputUsdPer1mTokens());
            }
            BigDecimal estimatedCost = calculateWorstCaseCost(
                    inputTokens,
                    outputTokens,
                    worstInputRate,
                    entry.outputUsdPer1mTokens()
            );
            if (ModelControlPlaneNumericValidation.violatesNonNegativeNumeric30Scale12(estimatedCost)) {
                return new PricingEstimate(null, false);
            }
            return new PricingEstimate(estimatedCost, true);
        }

        if ("FREE".equals(runtime.pricingStatus())) {
            return new PricingEstimate(BigDecimal.ZERO, true);
        }

        if (runtime.inputUsdPer1mTokens() == null
                || runtime.outputUsdPer1mTokens() == null) {
            return new PricingEstimate(null, false);
        }

        BigDecimal estimatedCost = calculateWorstCaseCost(
                inputTokens,
                outputTokens,
                runtime.inputUsdPer1mTokens(),
                runtime.outputUsdPer1mTokens()
        );
        if (ModelControlPlaneNumericValidation.violatesNonNegativeNumeric30Scale12(estimatedCost)) {
            return new PricingEstimate(null, false);
        }

        // Legacy flat runtime pricing cannot prove all billing dimensions.
        return new PricingEstimate(estimatedCost, false);
    }

    private static BigDecimal calculateWorstCaseCost(
            long inputTokens,
            long outputTokens,
            BigDecimal inputRate,
            BigDecimal outputRate
    ) {
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(inputRate)
                .divide(ONE_MILLION, MONEY_SCALE, RoundingMode.CEILING);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(outputRate)
                .divide(ONE_MILLION, MONEY_SCALE, RoundingMode.CEILING);

        return inputCost.add(outputCost).setScale(MONEY_SCALE, RoundingMode.CEILING);
    }

    record PricingEstimate(BigDecimal cost, boolean complete) {
        PricingEstimate {
            if (cost != null && ModelControlPlaneNumericValidation
                    .violatesNonNegativeNumeric30Scale12(cost)) {
                throw new IllegalArgumentException("Pricing estimate is outside numeric(30,12)");
            }
            if (complete && cost == null) {
                throw new IllegalArgumentException("Complete pricing estimate must have a cost");
            }
        }
    }

    record BudgetContext(
            boolean evaluated,
            boolean valid,
            BigDecimal actualCostUsd,
            BigDecimal reservedCostUsd,
            long unknownCommittedCostCount
    ) {
        BudgetContext {
            actualCostUsd = actualCostUsd == null ? BigDecimal.ZERO : actualCostUsd;
            reservedCostUsd = reservedCostUsd == null ? BigDecimal.ZERO : reservedCostUsd;
            if (actualCostUsd.signum() < 0 || reservedCostUsd.signum() < 0) {
                throw new IllegalArgumentException("Budget commitment components must be non-negative");
            }
            if (unknownCommittedCostCount < 0L) {
                throw new IllegalArgumentException("unknownCommittedCostCount must be non-negative");
            }
            if (!evaluated && (!valid
                    || actualCostUsd.signum() != 0
                    || reservedCostUsd.signum() != 0
                    || unknownCommittedCostCount != 0L)) {
                throw new IllegalArgumentException("Non-evaluated budget context must be empty");
            }
        }

        BigDecimal committedCostUsd() {
            return actualCostUsd.add(reservedCostUsd);
        }

        static BudgetContext none() {
            return new BudgetContext(false, true, BigDecimal.ZERO, BigDecimal.ZERO, 0L);
        }
    }

    record BudgetSnapshot(
            BigDecimal monthlyBudgetUsd,
            BigDecimal monthlyActualCostUsd,
            BigDecimal monthlyReservedCostUsd,
            long monthlyUnknownCostCount,
            BigDecimal monthlyProjectedUsd,
            boolean monthlyCostKnown,
            boolean exceeded,
            ModelRouteReason denialReason,
            MonthlyCommitmentState monthlyCommitmentState
    ) {
        BudgetSnapshot {
            Objects.requireNonNull(
                    monthlyCommitmentState,
                    "monthlyCommitmentState"
            );

            if (monthlyBudgetUsd == null) {
                if (monthlyActualCostUsd != null
                        || monthlyReservedCostUsd != null
                        || monthlyUnknownCostCount != 0L
                        || monthlyProjectedUsd != null
                        || monthlyCostKnown
                        || exceeded
                        || denialReason != null
                        || monthlyCommitmentState != MonthlyCommitmentState.NOT_EVALUATED) {
                    throw new IllegalArgumentException(
                            "Non-evaluated budget snapshot must be empty"
                    );
                }
            } else {
                if (monthlyBudgetUsd.signum() < 0
                        || monthlyUnknownCostCount < 0L
                        || (monthlyActualCostUsd != null
                            && monthlyActualCostUsd.signum() < 0)
                        || (monthlyReservedCostUsd != null
                            && monthlyReservedCostUsd.signum() < 0)
                        || (monthlyProjectedUsd != null
                            && monthlyProjectedUsd.signum() < 0)) {
                    throw new IllegalArgumentException(
                            "Budget snapshot values must be non-negative"
                    );
                }

                boolean actualPresent = monthlyActualCostUsd != null;
                boolean reservedPresent = monthlyReservedCostUsd != null;
                if (actualPresent != reservedPresent) {
                    throw new IllegalArgumentException(
                            "Actual/reserved commitment components must be present together"
                    );
                }

                if (monthlyCostKnown
                        && (!actualPresent
                            || monthlyProjectedUsd == null
                            || monthlyUnknownCostCount != 0L)) {
                    throw new IllegalArgumentException(
                            "Known monthly cost requires exact commitment and projection"
                    );
                }

                if (!monthlyCostKnown && monthlyProjectedUsd != null) {
                    throw new IllegalArgumentException(
                            "Unknown monthly cost cannot expose an exact projection"
                    );
                }

                if (denialReason == ModelRouteReason.MONTHLY_BUDGET_EXCEEDED
                        && !exceeded) {
                    throw new IllegalArgumentException(
                            "Budget-exceeded denial requires exceeded=true"
                    );
                }

                if (denialReason == ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
                        && (monthlyCostKnown || exceeded)) {
                    throw new IllegalArgumentException(
                            "Unverifiable budget denial requires unknown, unproven cost"
                    );
                }

                switch (monthlyCommitmentState) {
                    case KNOWN_WITHIN -> {
                        if (!monthlyCostKnown || exceeded) {
                            throw new IllegalArgumentException(
                                    "KNOWN_WITHIN requires known non-exceeded cost"
                            );
                        }
                    }
                    case KNOWN_EXCEEDED -> {
                        if (!monthlyCostKnown || !exceeded) {
                            throw new IllegalArgumentException(
                                    "KNOWN_EXCEEDED requires known exceeded cost"
                            );
                        }
                    }
                    case UNKNOWN_WITHIN_LOWER_BOUND -> {
                        if (monthlyCostKnown
                                || exceeded
                                || !actualPresent
                                || monthlyUnknownCostCount == 0L) {
                            throw new IllegalArgumentException(
                                    "UNKNOWN_WITHIN_LOWER_BOUND requires known components plus unknown liability"
                            );
                        }
                    }
                    case UNKNOWN_EXCEEDED_LOWER_BOUND -> {
                        if (monthlyCostKnown
                                || !exceeded
                                || !actualPresent
                                || monthlyUnknownCostCount == 0L) {
                            throw new IllegalArgumentException(
                                    "UNKNOWN_EXCEEDED_LOWER_BOUND requires unknown liability and proven overrun"
                            );
                        }
                    }
                    case UNVERIFIABLE -> {
                        if (monthlyCostKnown || exceeded) {
                            throw new IllegalArgumentException(
                                    "UNVERIFIABLE cannot claim exact cost or proven overrun"
                            );
                        }
                    }
                    case NOT_EVALUATED -> throw new IllegalArgumentException(
                            "Evaluated budget cannot be NOT_EVALUATED"
                    );
                }
            }
        }

        BigDecimal monthlyCommittedUsd() {
            if (monthlyActualCostUsd == null) {
                return null;
            }
            BigDecimal committed = monthlyActualCostUsd.add(monthlyReservedCostUsd);
            return ModelControlPlaneNumericValidation
                    .violatesNonNegativeNumeric30Scale12(committed)
                    ? null
                    : committed;
        }

        static BudgetSnapshot none() {
            return new BudgetSnapshot(
                    null, null, null, 0L, null, false, false, null,
                    MonthlyCommitmentState.NOT_EVALUATED
            );
        }
    }

}
