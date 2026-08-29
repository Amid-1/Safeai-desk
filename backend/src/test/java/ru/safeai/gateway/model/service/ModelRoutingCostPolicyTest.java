package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRoutingCostPolicyTest {

    @Mock
    private ModelRouteDecisionRepository decisionRepository;

    private ModelRoutingCostPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ModelRoutingCostPolicy(
                decisionRepository
        );
    }

    @Test
    void inputEstimateUsesUtf8BytesAndPerMessageOverhead() {
        ModelRouteRequest request = new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.TURN_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID,
                ModelTestFixtures.REQUEST_HASH,
                null,
                "é",
                List.of(
                        new AiMessage(
                                AiMessageRole.USER,
                                "A"
                        )
                ),
                Set.of()
        );

        // "é" = 2 UTF-8 bytes, "A" = 1 byte, two messages * 8 overhead.
        assertThat(policy.estimateInputTokens(request))
                .isEqualTo(19L);
    }

    @Test
    void effectiveLimitsUseTheMostRestrictiveRuntimeCatalogAndPolicyValue() {
        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();
        OrganizationModelPolicy tenantPolicy = ModelTestFixtures.policy(
                true,
                Set.of(),
                Set.of(),
                null,
                2_000,
                512,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );

        assertThat(policy.effectiveInputLimit(
                entry,
                ModelTestFixtures.freeRuntime(),
                tenantPolicy,
                true
        )).isEqualTo(2_000L);

        assertThat(policy.effectiveOutputLimit(
                entry,
                ModelTestFixtures.freeRuntime(),
                tenantPolicy,
                true
        )).isEqualTo(512L);
    }

    @Test
    void freeCatalogEntryProducesKnownZeroCost() {
        var estimate = policy.estimateCost(
                ModelTestFixtures.freeEntry(),
                ModelTestFixtures.freeRuntime(),
                1_000,
                500
        );

        assertThat(estimate.cost())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(estimate.complete())
                .isTrue();
    }

    @Test
    void configuredCatalogEntryCalculatesWorstCaseCostAtScaleTwelve() {
        var estimate = policy.estimateCost(
                ModelTestFixtures.configuredEntry(),
                ModelTestFixtures.configuredRuntime(),
                1_000,
                500
        );

        assertThat(estimate.cost())
                .isEqualByComparingTo("0.003000000000");
        assertThat(estimate.complete())
                .isTrue();
    }

    @Test
    void incompleteEntryWithMissingOrdinaryPriceIsUnknown() {
        ModelCatalogEntry entry = ModelTestFixtures.entry(
                ru.safeai.gateway.model.domain.ModelLifecycle.ACTIVE,
                ModelPricingStatus.INCOMPLETE,
                false,
                null,
                null,
                Set.of(),
                ru.safeai.gateway.model.domain.ModelRetentionStatus.NOT_DECLARED,
                ru.safeai.gateway.model.domain.ModelTrainingUseStatus.NOT_DECLARED
        );

        var estimate = policy.estimateCost(
                entry,
                ModelTestFixtures.configuredRuntime(),
                10,
                10
        );

        assertThat(estimate.cost())
                .isNull();
        assertThat(estimate.complete())
                .isFalse();
    }

    @Test
    void legacyConfiguredRuntimeCanEstimateButNeverClaimsCompletePricing() {
        var estimate = policy.estimateCost(
                null,
                ModelTestFixtures.configuredRuntime(),
                1_000,
                500
        );

        assertThat(estimate.cost())
                .isEqualByComparingTo("0.003000000000");
        assertThat(estimate.complete())
                .isFalse();
    }

    @Test
    void budgetIsSkippedWhenPolicyIsDisabled() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.hardPolicy();

        var snapshot = policy.evaluateBudget(
                ModelTestFixtures.ORGANIZATION_ID,
                tenantPolicy,
                false,
                BigDecimal.ONE,
                ModelTestFixtures.NOW
        );

        assertThat(snapshot.monthlyBudgetUsd())
                .isNull();
        assertThat(snapshot.monthlyCostKnown())
                .isTrue();
        verify(decisionRepository, never())
                .lockOrganizationBudget(
                        ModelTestFixtures.ORGANIZATION_ID
                );
    }

    @Test
    void hardBudgetFailsClosedWhenCommittedCostIsUnknown() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.hardPolicy();
        stubMonthlySnapshot(
                new BigDecimal("20.000000000000"),
                1L
        );

        var snapshot = policy.evaluateBudget(
                ModelTestFixtures.ORGANIZATION_ID,
                tenantPolicy,
                true,
                BigDecimal.ONE,
                ModelTestFixtures.NOW
        );

        assertThat(snapshot.monthlyCostKnown())
                .isFalse();
        assertThat(snapshot.denialReason())
                .isEqualTo(
                        ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
                );
    }

    @Test
    void hardBudgetRejectsProjectedSpendAboveLimit() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.hardPolicy();
        stubMonthlySnapshot(
                new BigDecimal("99.500000000000"),
                0L
        );

        var snapshot = policy.evaluateBudget(
                ModelTestFixtures.ORGANIZATION_ID,
                tenantPolicy,
                true,
                new BigDecimal("1.000000000000"),
                ModelTestFixtures.NOW
        );

        assertThat(snapshot.monthlyProjectedUsd())
                .isEqualByComparingTo("100.500000000000");
        assertThat(snapshot.exceeded())
                .isTrue();
        assertThat(snapshot.denialReason())
                .isEqualTo(
                        ModelRouteReason.MONTHLY_BUDGET_EXCEEDED
                );
    }

    @Test
    void softBudgetRecordsExceededStateWithoutDenying() {
        OrganizationModelPolicy tenantPolicy = ModelTestFixtures.policy(
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                new BigDecimal("10.000000000000"),
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
        stubMonthlySnapshot(
                new BigDecimal("9.500000000000"),
                0L
        );

        var snapshot = policy.evaluateBudget(
                ModelTestFixtures.ORGANIZATION_ID,
                tenantPolicy,
                true,
                new BigDecimal("1.000000000000"),
                ModelTestFixtures.NOW
        );

        assertThat(snapshot.exceeded())
                .isTrue();
        assertThat(snapshot.denialReason())
                .isNull();
    }

    @Test
    void budgetWindowUsesUtcCalendarMonthBoundaries() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.hardPolicy();
        Instant instant = Instant.parse(
                "2026-08-31T23:59:59Z"
        );

        when(decisionRepository.loadCommittedMonthlyCostSnapshot(
                ModelTestFixtures.ORGANIZATION_ID,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z")
        )).thenReturn(
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                        BigDecimal.ZERO,
                        0L
                )
        );

        policy.evaluateBudget(
                ModelTestFixtures.ORGANIZATION_ID,
                tenantPolicy,
                true,
                BigDecimal.ZERO,
                instant
        );

        verify(decisionRepository)
                .loadCommittedMonthlyCostSnapshot(
                        ModelTestFixtures.ORGANIZATION_ID,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z")
                );
    }

    private void stubMonthlySnapshot(
            BigDecimal committed,
            long unknownCount
    ) {
        when(decisionRepository.loadCommittedMonthlyCostSnapshot(
                ModelTestFixtures.ORGANIZATION_ID,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z")
        )).thenReturn(
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                        committed,
                        unknownCount
                )
        );
    }
}
