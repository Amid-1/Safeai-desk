package ru.safeai.gateway.model.dto;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelBudgetCommitmentSnapshot;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.MonthlyCommitmentState;
import ru.safeai.gateway.model.domain.MonthlyCostState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouteDecisionResponseTest {

    @Test
    void wireResponsePreservesExactDecimalStringsAndDerivedMonthlyState() {
        ModelRouteDecision decision =
                new ModelRouteDecision(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "a".repeat(64),
                        null,
                        UUID.randomUUID(),
                        7,
                        "openai:gpt-test",
                        "openai",
                        "gpt-test",
                        null,
                        null,
                        Set.of(),
                        "test-accounting-v1",
                        100L,
                        200L,
                        300L,
                        new BigDecimal(
                                "1.230000000000"
                        ),
                        new BigDecimal(
                                "10.000000000000"
                        ),
                        new BigDecimal(
                                "4.500000000000"
                        ),
                        null,
                        false,
                        BudgetEnforcement.HARD,
                        false,
                        true,
                        ModelRouteOutcome.DENIED,
                        ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE,
                        (short) 3,
                        "b".repeat(64),
                        Instant.parse(
                                "2026-10-03T12:00:00Z"
                        )
                );

        ModelBudgetCommitmentSnapshot commitment =
                new ModelBudgetCommitmentSnapshot(
                        new BigDecimal(
                                "4.000000000000"
                        ),
                        new BigDecimal(
                                "0.500000000000"
                        ),
                        1L,
                        MonthlyCommitmentState.UNVERIFIABLE
                );

        ModelRouteDecisionResponse response =
                ModelRouteDecisionResponse.from(
                        decision,
                        commitment
                );

        assertThat(
                response.estimatedMaxCostUsd()
        ).isEqualTo(
                "1.230000000000"
        );

        assertThat(
                response.monthlyBudgetUsd()
        ).isEqualTo(
                "10.000000000000"
        );

        assertThat(
                response.monthlyActualCostUsd()
        ).isEqualTo(
                "4.000000000000"
        );

        assertThat(
                response.monthlyReservedCostUsd()
        ).isEqualTo(
                "0.500000000000"
        );

        assertThat(
                response.monthlyCommittedUsd()
        ).isEqualTo(
                "4.500000000000"
        );

        assertThat(
                response.monthlySpentUsd()
        ).isEqualTo(
                "4.500000000000"
        );

        assertThat(
                response.monthlyUnknownCostCount()
        ).isEqualTo(
                1L
        );

        assertThat(
                response.monthlyProjectedUsd()
        ).isNull();

        assertThat(
                response.monthlyCostKnown()
        ).isFalse();

        assertThat(
                response.monthlyCostState()
        ).isEqualTo(
                MonthlyCostState.UNKNOWN
        );

        assertThat(
                response.monthlyCommitmentState()
        ).isEqualTo(
                MonthlyCommitmentState.UNVERIFIABLE
        );

        assertThat(
                response.decisionIntegrityVersion()
        ).isEqualTo(
                (short) 3
        );

        assertThat(
                response.decisionSha256()
        ).isEqualTo(
                "b".repeat(64)
        );
    }

    @Test
    void previewMoneyFormatterNeverConvertsThroughBinaryFloatingPoint() {
        BigDecimal exact =
                new BigDecimal(
                        "0.123456789012"
                );

        assertThat(
                ModelPolicyPreviewItemResponse.decimal(
                        exact
                )
        ).isEqualTo(
                "0.123456789012"
        );
    }
}
