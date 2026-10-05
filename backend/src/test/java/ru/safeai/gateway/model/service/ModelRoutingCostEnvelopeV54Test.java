package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Financial governance must not assume cached/cache-write rates are less than
 * or equal to the ordinary input rate.
 */
@Tag("unit")
class ModelRoutingCostEnvelopeV54Test {

    private final ModelRoutingCostPolicy policy =
            new ModelRoutingCostPolicy(
                    mock(ModelRouteDecisionRepository.class)
            );

    @Test
    void cacheWriteRateMayExceedOrdinaryInputAndMustRaiseUpperBoundEstimate() {
        ModelCatalogEntry base =
                ModelTestFixtures.configuredEntry();

        ModelCatalogEntry entry =
                new ModelCatalogEntry(
                        base.id(),
                        base.modelKey(),
                        base.version(),
                        base.provider(),
                        base.providerModelId(),
                        base.displayName(),
                        base.lifecycle(),
                        base.maxInputTokens(),
                        base.maxOutputTokens(),
                        base.capabilities(),
                        base.inputModalities(),
                        base.outputModalities(),
                        base.retentionStatus(),
                        base.retentionDays(),
                        base.trainingUseStatus(),
                        base.pricingStatus(),
                        base.pricingComplete(),
                        new BigDecimal("2"),
                        new BigDecimal("0.5"),
                        new BigDecimal("4"),
                        new BigDecimal("8"),
                        base.extraPricingJson(),
                        base.pricingVersion(),
                        base.effectiveFrom(),
                        base.source(),
                        base.createdByUserId(),
                        base.createdAt()
                );

        ModelRoutingCostPolicy.PricingEstimate estimate =
                policy.estimateCost(
                        entry,
                        ModelTestFixtures.configuredRuntime(),
                        20_000L,
                        1_000L
                );

        assertThat(estimate.complete()).isTrue();

        /*
         * All input is conservatively priced at
         * max(2, 0.5, 4) = $4 / 1M input units:
         *
         * 20,000 * 4 / 1,000,000 = 0.080
         *  1,000 * 8 / 1,000,000 = 0.008
         * total                      0.088
         */
        assertThat(
                estimate.cost()
        ).isEqualByComparingTo(
                "0.088000000000"
        );
    }

    @Test
    void hardBudgetDoesNotAllowExecutionWhenHistoricalPhysicalCostIsUnknown() {
        /*
         * V64+ accounting keeps exact known actual and reserved commitments
         * separately from unknown liability.
         */
        ModelRoutingCostPolicy.BudgetContext context =
                new ModelRoutingCostPolicy.BudgetContext(
                        true,
                        true,
                        new BigDecimal("1.0"),
                        BigDecimal.ZERO,
                        1L
                );

        ModelRoutingCostPolicy.PricingEstimate estimate =
                new ModelRoutingCostPolicy.PricingEstimate(
                        new BigDecimal("0.03"),
                        true
                );

        ModelRoutingCostPolicy.BudgetSnapshot result =
                policy.evaluateBudget(
                        context,
                        ModelTestFixtures.hardPolicy(),
                        estimate
                );

        assertThat(
                result.denialReason()
        ).isEqualTo(
                ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
        );

        assertThat(result.monthlyCostKnown()).isFalse();
        assertThat(result.monthlyProjectedUsd()).isNull();

        assertThat(
                result.monthlyActualCostUsd()
        ).isEqualByComparingTo(
                "1.0"
        );

        assertThat(
                result.monthlyReservedCostUsd()
        ).isEqualByComparingTo(
                BigDecimal.ZERO
        );

        assertThat(
                result.monthlyUnknownCostCount()
        ).isEqualTo(
                1L
        );
    }
}
