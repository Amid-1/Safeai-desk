// backend/src/test/java/ru/safeai/gateway/model/pricing/VersionedCatalogPricingResolverTest.java
package ru.safeai.gateway.model.pricing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanEntity;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanRepository;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.ai.pricing.PricingResult;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Physical settlement must use the exact immutable catalog price book.
 * V66: historical CONFIGURED prices are explicitly unverified.
 * The tests never reclassify legacy prices implicitly or weaken fail-closed behavior.
 */
@Tag("unit")
class VersionedCatalogPricingResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final UUID OPERATION = UUID.randomUUID();
    private static final UUID TURN = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final ProviderExecutionTarget TARGET =
            ProviderExecutionTarget.staticTarget("openai", "gpt-test");

    private final ModelExecutionPlanRepository plans =
            mock(ModelExecutionPlanRepository.class);
    private final ModelRouteDecisionRepository decisions =
            mock(ModelRouteDecisionRepository.class);
    private final ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
    private final VersionedCatalogPricingResolver resolver =
            new VersionedCatalogPricingResolver(
                    plans, decisions, catalog, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void priceCannotBeProvenWithoutDurableProviderOperationIdentity() {
        PricingResult result = resolver.resolve(TARGET, "gpt-test", plain());

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
        verifyNoInteractions(plans, decisions, catalog);
    }

    @Test
    void exactCatalogVersionIsUsedForPhysicalSettlementAndExposesVersionedPriceBook() {
        ModelCatalogEntry entry = verifiedConfiguredEntry();
        allow(entry);

        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", plain());

        // 1000 * $1/1M + 500 * $4/1M = $0.003.
        assertThat(entry.pricingScheme()).isEqualTo(ModelPricingScheme.FLAT_TOKEN_V1);
        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd()).isEqualByComparingTo("0.003000000000");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.priceVersion()).startsWith("cat-v1-").hasSize(63);
        assertThat(resolver.resolve(OPERATION, TARGET, "gpt-test", plain()).priceVersion())
                .isEqualTo(result.priceVersion());
    }

    @Test
    void physicalResolvedModelMismatchMustNeverBePricedAsRequestedModel() {
        ModelCatalogEntry entry = verifiedConfiguredEntry();
        allow(entry);

        PricingResult result = resolver.resolve(
                OPERATION, TARGET, "gpt-test-unknown", plain());

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
        verifyNoInteractions(decisions, catalog);
    }

    @Test
    void catalogVersionMismatchCannotSilentlyUseNewOrStaleRates() {
        ModelCatalogEntry entry = verifiedConfiguredEntry();
        ModelRouteDecision decision = decision(entry);
        when(decision.selectedCatalogVersion()).thenReturn(entry.version() + 1);
        allowPlanAndDecision(decision);
        when(catalog.findById(entry.id())).thenReturn(Optional.of(entry));

        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", plain());

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    @Test
    void openAiCacheWriteRateAboveOrdinaryInputIsValidAndNotDoubleCounted() {
        ModelCatalogEntry entry = withCacheRatesAboveInput(verifiedConfiguredEntry());
        allow(entry);

        AiTokenUsage usage = new AiTokenUsage(20_000, 15_000, 2_000, 1_000, true, true);
        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", usage);

        // 3000 ordinary * $2/1M + 15000 cache read * $0.5/1M
        // + 2000 cache write * $4/1M + 1000 output * $8/1M = $0.0295.
        assertThat(entry.pricingScheme()).isEqualTo(ModelPricingScheme.FLAT_TOKEN_V1);
        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd()).isEqualByComparingTo("0.029500000000");
    }

    @Test
    void invalidOrUndocumentedSpecializedDimensionsNeverClaimKnownCost() {
        allow(withCacheRatesAboveInput(verifiedConfiguredEntry()));

        assertThat(resolver.resolve(OPERATION, TARGET, "gpt-test",
                new AiTokenUsage(100, 20, 0, 10, true, false)).status())
                .isEqualTo(PricingStatus.UNPRICED);
        assertThat(resolver.resolve(OPERATION, TARGET, "gpt-test",
                new AiTokenUsage(100, 110, 0, 10, true, true)).status())
                .isEqualTo(PricingStatus.UNPRICED);
        assertThat(resolver.resolve(OPERATION, TARGET, "gpt-test",
                new AiTokenUsage(100, 20, 0, 10, true, true)).status())
                .isEqualTo(PricingStatus.PRICED);
    }

    @Test
    void noCacheRateMeansUnpricedNotOrdinaryInputApproximation() {
        allow(verifiedConfiguredEntry());

        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test",
                new AiTokenUsage(100, 20, 0, 10, true, true));

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    @Test
    void legacyConfiguredCatalogMustRemainUnpricedDespiteValidTokenUsage() {
        // Intentionally use the historical constructor-backed fixture.
        // It marks CONFIGURED prices as LEGACY_FLAT_UNVERIFIED_V1.
        ModelCatalogEntry legacy = ModelTestFixtures.configuredEntry();
        assertThat(legacy.pricingScheme())
                .isEqualTo(ModelPricingScheme.LEGACY_FLAT_UNVERIFIED_V1);
        allow(legacy);

        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", plain());

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    @Test
    void unsupportedPricingSchemeMustNotBeSettledAsFlatTokenPricing() {
        ModelCatalogEntry unsupported = withPricing(
                ModelTestFixtures.configuredEntry(),
                ModelPricingScheme.UNSUPPORTED_COMPLEX_V1,
                new BigDecimal("1"), null, null, new BigDecimal("4"));
        allow(unsupported);

        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", plain());

        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    private void allow(ModelCatalogEntry entry) {
        ModelRouteDecision selected = decision(entry);
        allowPlanAndDecision(selected);
        when(catalog.findById(entry.id())).thenReturn(Optional.of(entry));
    }

    private void allowPlanAndDecision(ModelRouteDecision selected) {
        when(plans.findByProviderOperationId(OPERATION)).thenReturn(Optional.of(
                ModelExecutionPlanEntity.create(
                        OPERATION, TURN, ORG, DECISION_ID, "gpt-test", NOW)));
        when(decisions.findById(DECISION_ID)).thenReturn(Optional.of(selected));
    }

    private ModelRouteDecision decision(ModelCatalogEntry entry) {
        ModelRouteDecision selected = mock(ModelRouteDecision.class);
        when(selected.outcome()).thenReturn(ModelRouteOutcome.ALLOWED);
        when(selected.decisionIntegrityVersion()).thenReturn((short) 3);
        when(selected.selectedCatalogEntryId()).thenReturn(entry.id());
        when(selected.chatTurnId()).thenReturn(TURN);
        when(selected.organizationId()).thenReturn(ORG);
        when(selected.selectedProvider()).thenReturn("openai");
        when(selected.selectedProviderModelId()).thenReturn("gpt-test");
        when(selected.selectedCatalogVersion()).thenReturn(entry.version());
        when(selected.selectedModelKey()).thenReturn(entry.modelKey());
        return selected;
    }

    private static AiTokenUsage plain() {
        return new AiTokenUsage(1_000, null, null, 500, false, true);
    }

    /** Only this pricing-specific fixture explicitly opts into the V66 supported scheme. */
    private static ModelCatalogEntry verifiedConfiguredEntry() {
        ModelCatalogEntry base = ModelTestFixtures.configuredEntry();
        return withPricing(base, ModelPricingScheme.FLAT_TOKEN_V1,
                base.inputUsdPer1mTokens(), base.cachedInputUsdPer1mTokens(),
                base.cacheWriteInputUsdPer1mTokens(), base.outputUsdPer1mTokens());
    }

    private static ModelCatalogEntry withCacheRatesAboveInput(ModelCatalogEntry base) {
        // Preserve the scheme while changing the four price dimensions.
        return withPricing(base, base.pricingScheme(),
                new BigDecimal("2"), new BigDecimal("0.5"),
                new BigDecimal("4"), new BigDecimal("8"));
    }

    private static ModelCatalogEntry withPricing(
            ModelCatalogEntry base,
            ModelPricingScheme scheme,
            BigDecimal inputRate,
            BigDecimal cacheReadRate,
            BigDecimal cacheWriteRate,
            BigDecimal outputRate
    ) {
        // Use the full V66 constructor. The deprecated 29-arg overload would
        // reclassify CONFIGURED prices as unverified, even when cloning a verified entry.
        return new ModelCatalogEntry(
                base.id(), base.modelKey(), base.version(), base.provider(),
                base.providerModelId(), base.displayName(), base.lifecycle(),
                base.maxInputTokens(), base.maxOutputTokens(), base.capabilities(),
                base.inputModalities(), base.outputModalities(), base.retentionStatus(),
                base.retentionDays(), base.trainingUseStatus(), base.pricingStatus(),
                base.pricingComplete(), inputRate, cacheReadRate, cacheWriteRate,
                outputRate, base.extraPricingJson(), base.pricingVersion(),
                base.effectiveFrom(), base.source(), base.createdByUserId(),
                base.createdAt(), scheme);
    }
}
