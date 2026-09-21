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

/** Financial governance must not assume cached/cache-write rates <= input. */
@Tag("unit")
class ModelRoutingCostEnvelopeV54Test {
    private final ModelRoutingCostPolicy policy =
            new ModelRoutingCostPolicy(mock(ModelRouteDecisionRepository.class));

    @Test
    void cacheWriteRateMayExceedOrdinaryInputAndMustRaiseUpperBoundEstimate() {
        ModelCatalogEntry b = ModelTestFixtures.configuredEntry();
        ModelCatalogEntry entry = new ModelCatalogEntry(
                b.id(), b.modelKey(), b.version(), b.provider(), b.providerModelId(),
                b.displayName(), b.lifecycle(), b.maxInputTokens(), b.maxOutputTokens(),
                b.capabilities(), b.inputModalities(), b.outputModalities(),
                b.retentionStatus(), b.retentionDays(), b.trainingUseStatus(),
                b.pricingStatus(), b.pricingComplete(), new BigDecimal("2"),
                new BigDecimal("0.5"), new BigDecimal("4"), new BigDecimal("8"),
                b.extraPricingJson(), b.pricingVersion(), b.effectiveFrom(),
                b.source(), b.createdByUserId(), b.createdAt());
        ModelRoutingCostPolicy.PricingEstimate estimate = policy.estimateCost(
                entry, ModelTestFixtures.configuredRuntime(), 20_000L, 1_000L);
        assertThat(estimate.complete()).isTrue();
        // All input conservatively priced at max(2, .5, 4) = $4/1M.
        assertThat(estimate.cost()).isEqualByComparingTo("0.088000000000");
    }

    @Test
    void hardBudgetDoesNotAllowExecutionWhenHistoricalPhysicalCostIsUnknown() {
        var context = new ModelRoutingCostPolicy.BudgetContext(
                true, true, new BigDecimal("1.0"), 1L);
        var estimate = new ModelRoutingCostPolicy.PricingEstimate(
                new BigDecimal("0.03"), true);
        var result = policy.evaluateBudget(context, ModelTestFixtures.hardPolicy(), estimate);
        assertThat(result.denialReason())
                .isEqualTo(ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE);
        assertThat(result.monthlyCostKnown()).isFalse();
        assertThat(result.monthlyProjectedUsd()).isNull();
    }
}
