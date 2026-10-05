package ru.safeai.gateway.model.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouteDecisionMonthlyCostStateTest {

    @Test
    void absentMonthlyBudgetMeansNotEvaluatedEvenWhenEnforcementModeExists() {
        ModelRouteDecision decision = decision(null, false);
        assertThat(decision.monthlyCostState()).isEqualTo(MonthlyCostState.NOT_EVALUATED);
    }

    @Test
    void evaluatedKnownMonthlyCostIsReportedAsKnown() {
        ModelRouteDecision decision = decision(new BigDecimal("100.000000000000"), true);
        assertThat(decision.monthlyCostState()).isEqualTo(MonthlyCostState.KNOWN);
    }

    @Test
    void evaluatedUnknownMonthlyCostIsReportedAsUnknown() {
        ModelRouteDecision decision = decision(new BigDecimal("100.000000000000"), false);
        assertThat(decision.monthlyCostState()).isEqualTo(MonthlyCostState.UNKNOWN);
    }

    private static ModelRouteDecision decision(
            BigDecimal monthlyBudget,
            boolean monthlyCostKnown
    ) {
        return new ModelRouteDecision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                null,
                UUID.randomUUID(),
                1,
                "openai:gpt-test",
                "openai",
                "gpt-test",
                null,
                null,
                Set.of(),
                "test-input-accounting-v1",
                0L,
                10L,
                10L,
                BigDecimal.ZERO,
                monthlyBudget,
                monthlyBudget == null ? null : BigDecimal.ZERO,
                monthlyBudget == null || !monthlyCostKnown ? null : BigDecimal.ZERO,
                monthlyCostKnown,
                BudgetEnforcement.HARD,
                false,
                true,
                ModelRouteOutcome.ALLOWED,
                ModelRouteReason.REQUESTED_MODEL,
                (short) 3,
                "b".repeat(64),
                Instant.parse("2026-10-03T12:00:00Z")
        );
    }
}
