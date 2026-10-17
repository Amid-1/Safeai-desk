package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.model.domain.*;
import ru.safeai.gateway.model.dto.ModelCatalogEntryResponse;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ModelCatalogPricingSchemeTest {
    @Test
    void legacyConfiguredCannotSilentlyAuthorizePricingOrBudget() {
        ModelCatalogEntry legacy = entry(null);
        assertThat(legacy.pricingScheme())
                .isEqualTo(ModelPricingScheme.LEGACY_FLAT_UNVERIFIED_V1);
        assertThat(ModelCatalogEntryResponse.from(legacy).pricingScheme())
                .isEqualTo(ModelPricingScheme.LEGACY_FLAT_UNVERIFIED_V1);
        ModelRoutingCostPolicy cost = new ModelRoutingCostPolicy(
                mock(ModelRouteDecisionRepository.class));
        ModelRoutingCostPolicy.PricingEstimate estimated =
                cost.estimateCost(legacy, null, 100, 20);
        assertThat(estimated.complete()).isFalse();
        assertThat(estimated.cost()).isNull();
    }

    @Test
    void explicitlyVerifiedTariffCanBeEstimated() {
        ModelCatalogEntry verified = entry(ModelPricingScheme.FLAT_TOKEN_V1);
        ModelRoutingCostPolicy cost = new ModelRoutingCostPolicy(
                mock(ModelRouteDecisionRepository.class));
        ModelRoutingCostPolicy.PricingEstimate estimate =
                cost.estimateCost(verified, null, 100, 20);
        assertThat(estimate.complete()).isTrue();
        assertThat(estimate.cost()).isPositive();
        assertThat(ModelCatalogEntryResponse.from(verified).pricingScheme())
                .isEqualTo(ModelPricingScheme.FLAT_TOKEN_V1);
    }

    private static ModelCatalogEntry entry(ModelPricingScheme scheme) {
        UUID id = UUID.randomUUID();
        ModelCatalogEntry legacy = new ModelCatalogEntry(
                id, "openai:test", 1, "openai", "test", "Test",
                ModelLifecycle.ACTIVE, 1000, 1000,
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED, null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.CONFIGURED, true,
                new BigDecimal("1.0"), null, null, new BigDecimal("3.0"),
                "{}", "verified-2026", Instant.parse("2026-10-17T00:00:00Z"),
                ModelCatalogSource.MANUAL, UUID.randomUUID(),
                Instant.parse("2026-10-17T00:00:00Z"));
        if (scheme == null) return legacy;
        return new ModelCatalogEntry(
                legacy.id(), legacy.modelKey(), legacy.version(), legacy.provider(),
                legacy.providerModelId(), legacy.displayName(), legacy.lifecycle(),
                legacy.maxInputTokens(), legacy.maxOutputTokens(),
                legacy.capabilities(), legacy.inputModalities(), legacy.outputModalities(),
                legacy.retentionStatus(), legacy.retentionDays(),
                legacy.trainingUseStatus(), legacy.pricingStatus(), legacy.pricingComplete(),
                legacy.inputUsdPer1mTokens(), legacy.cachedInputUsdPer1mTokens(),
                legacy.cacheWriteInputUsdPer1mTokens(), legacy.outputUsdPer1mTokens(),
                legacy.extraPricingJson(), legacy.pricingVersion(),
                legacy.effectiveFrom(), legacy.source(), legacy.createdByUserId(),
                legacy.createdAt(), scheme);
    }
}
