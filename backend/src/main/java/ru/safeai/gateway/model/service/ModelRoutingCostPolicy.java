package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiMessage;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

final class ModelRoutingCostPolicy {

    private static final BigDecimal ONE_MILLION =
            new BigDecimal("1000000");
    private static final int MONEY_SCALE = 12;
    private static final int TOKEN_ESTIMATE_OVERHEAD_PER_MESSAGE = 8;

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
            BigDecimal currentEstimatedCost,
            Instant now
    ) {
        if (!policyEnabled) {
            return BudgetSnapshot.none();
        }

        Objects.requireNonNull(
                policy,
                "policy не должен быть null при policyEnabled=true"
        );

        if (policy.monthlyBudgetUsd() == null) {
            return BudgetSnapshot.none();
        }

        decisionRepository.lockOrganizationBudget(organizationId);

        Instant periodStart = YearMonth
                .from(now.atZone(ZoneOffset.UTC))
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Instant periodEnd = YearMonth
                .from(now.atZone(ZoneOffset.UTC))
                .plusMonths(1)
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
        if (ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(spent)) {
            return unverifiableBudget(policy);
        }

        long unknownCommitted =
                commitment.unknownCommittedCostCount();

        boolean costKnown = unknownCommitted == 0L
                && currentEstimatedCost != null;
        BigDecimal projected = costKnown
                ? spent.add(currentEstimatedCost)
                : null;

        if (projected != null
                && ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(projected)) {
            return unverifiableBudget(policy);
        }

        boolean exceeded = projected != null
                && projected.compareTo(policy.monthlyBudgetUsd()) > 0;

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

        if (policy.budgetEnforcement() == BudgetEnforcement.HARD
                && exceeded) {
            return new BudgetSnapshot(
                    policy.monthlyBudgetUsd(),
                    spent,
                    projected,
                    true,
                    true,
                    ModelRouteReason.MONTHLY_BUDGET_EXCEEDED
            );
        }

        return new BudgetSnapshot(
                policy.monthlyBudgetUsd(),
                spent,
                projected,
                costKnown,
                exceeded,
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
     * Deliberately over-estimates plain text input using UTF-8 bytes rather
     * than a provider tokenizer. HARD budget checks therefore do not depend on
     * provider tokenizer implementation changes.
     */
    long estimateInputTokens(
            ModelRouteRequest request
    ) {
        long estimate = utf8Length(request.userMessage())
                + TOKEN_ESTIMATE_OVERHEAD_PER_MESSAGE;

        for (AiMessage message : request.history()) {
            estimate = Math.addExact(
                    estimate,
                    utf8Length(message.content())
                            + TOKEN_ESTIMATE_OVERHEAD_PER_MESSAGE
            );
        }

        return estimate;
    }

    PricingEstimate estimateCost(
            ModelCatalogEntry entry,
            RuntimeModelStatusResponse runtime,
            long inputTokens,
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

            BigDecimal estimatedCost = calculateWorstCaseCost(
                    inputTokens,
                    outputTokens,
                    entry.inputUsdPer1mTokens(),
                    entry.outputUsdPer1mTokens()
            );
            if (ModelControlPlaneNumericValidation
                    .violatesNonNegativeNumeric30Scale12(estimatedCost)) {
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

        /*
         * Legacy runtime pricing can estimate ordinary input/output but cannot
         * prove cached/cache-write/extra billing dimensional completeness.
         */
        BigDecimal estimatedCost = calculateWorstCaseCost(
                inputTokens,
                outputTokens,
                runtime.inputUsdPer1mTokens(),
                runtime.outputUsdPer1mTokens()
        );
        if (ModelControlPlaneNumericValidation
                .violatesNonNegativeNumeric30Scale12(estimatedCost)) {
            return new PricingEstimate(null, false);
        }

        return new PricingEstimate(
                estimatedCost,
                false
        );
    }

    private static long utf8Length(
            String value
    ) {
        return value == null
                ? 0L
                : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static BigDecimal calculateWorstCaseCost(
            long inputTokens,
            long outputTokens,
            BigDecimal inputRate,
            BigDecimal outputRate
    ) {
        BigDecimal inputCost = BigDecimal
                .valueOf(inputTokens)
                .multiply(inputRate)
                .divide(
                        ONE_MILLION,
                        MONEY_SCALE,
                        RoundingMode.HALF_UP
                );
        BigDecimal outputCost = BigDecimal
                .valueOf(outputTokens)
                .multiply(outputRate)
                .divide(
                        ONE_MILLION,
                        MONEY_SCALE,
                        RoundingMode.HALF_UP
                );

        return inputCost
                .add(outputCost)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
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
                    true,
                    false,
                    null
            );
        }

        BudgetSnapshot withFlags(
                boolean costKnown,
                boolean budgetExceeded
        ) {
            return new BudgetSnapshot(
                    monthlyBudgetUsd,
                    monthlySpentUsd,
                    monthlyProjectedUsd,
                    costKnown,
                    budgetExceeded,
                    denialReason
            );
        }
    }
}
