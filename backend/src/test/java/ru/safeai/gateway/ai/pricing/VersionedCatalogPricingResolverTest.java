package ru.safeai.gateway.ai.pricing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanEntity;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanRepository;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
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
import static org.mockito.Mockito.*;

/** V54: actual physical cost comes from the immutable selected catalog version, not YAML. */
@Tag("unit")
class VersionedCatalogPricingResolverTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final UUID OPERATION = UUID.randomUUID();
    private static final UUID TURN = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final ProviderExecutionTarget TARGET =
            ProviderExecutionTarget.staticTarget("openai", "gpt-test");

    private final ModelExecutionPlanRepository plans = mock(ModelExecutionPlanRepository.class);
    private final ModelRouteDecisionRepository decisions = mock(ModelRouteDecisionRepository.class);
    private final ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
    private final VersionedCatalogPricingResolver resolver = new VersionedCatalogPricingResolver(
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
        ModelCatalogEntry entry = ModelTestFixtures.configuredEntry();
        allow(entry);
        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", plain());
        // 1000 * $1/1M + 500 * $4/1M = $0.003
        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd()).isEqualByComparingTo("0.003000000000");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.priceVersion()).startsWith("cat-v1-").hasSize(63);
        assertThat(resolver.resolve(OPERATION, TARGET, "gpt-test", plain()).priceVersion())
                .isEqualTo(result.priceVersion());
    }

    @Test
    void physicalResolvedModelMismatchMustNeverBePricedAsRequestedModel() {
        ModelCatalogEntry entry = ModelTestFixtures.configuredEntry();
        allow(entry);
        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test-unknown", plain());
        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        verifyNoInteractions(decisions, catalog);
    }

    @Test
    void catalogVersionMismatchCannotSilentlyUseNewOrStaleRates() {
        ModelCatalogEntry entry = ModelTestFixtures.configuredEntry();
        ModelRouteDecision decision = decision(entry);
        when(decision.selectedCatalogVersion()).thenReturn(entry.version() + 1);
        allowPlanAndDecision(decision);
        when(catalog.findById(entry.id())).thenReturn(Optional.of(entry));
        assertThat(resolver.resolve(OPERATION, TARGET, "gpt-test", plain()).status())
                .isEqualTo(PricingStatus.UNPRICED);
    }

    @Test
    void openAiCacheWriteRateAboveOrdinaryInputIsValidAndNotDoubleCounted() {
        ModelCatalogEntry base = ModelTestFixtures.configuredEntry();
        ModelCatalogEntry entry = withCacheRatesAboveInput(base);
        allow(entry);
        AiTokenUsage usage = new AiTokenUsage(20_000, 15_000, 2_000, 1_000, true, true);
        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test", usage);
        // 3000 ordinary * 2 + 15000 cached * .5 + 2000 write * 4 + 1000 out * 8 = .0295
        assertThat(result.status()).isEqualTo(PricingStatus.PRICED);
        assertThat(result.costUsd()).isEqualByComparingTo("0.029500000000");
    }

    @Test
    void invalidOrUndocumentedSpecializedDimensionsNeverClaimKnownCost() {
        allow(withCacheRatesAboveInput(ModelTestFixtures.configuredEntry()));
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
        allow(ModelTestFixtures.configuredEntry());
        PricingResult result = resolver.resolve(OPERATION, TARGET, "gpt-test",
                new AiTokenUsage(100, 20, 0, 10, true, true));
        assertThat(result.status()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(result.costUsd()).isNull();
    }

    private void allow(ModelCatalogEntry entry) {
        ModelRouteDecision decision = decision(entry);
        allowPlanAndDecision(decision);
        when(catalog.findById(entry.id())).thenReturn(Optional.of(entry));
    }

    private void allowPlanAndDecision(ModelRouteDecision decision) {
        when(plans.findByProviderOperationId(OPERATION)).thenReturn(Optional.of(
                ModelExecutionPlanEntity.create(OPERATION, TURN, ORG,
                        DECISION_ID, "gpt-test", NOW)));
        when(decisions.findById(DECISION_ID)).thenReturn(Optional.of(decision));
    }

    private ModelRouteDecision decision(ModelCatalogEntry entry) {
        ModelRouteDecision decision = mock(ModelRouteDecision.class);
        when(decision.outcome()).thenReturn(ModelRouteOutcome.ALLOWED);
        when(decision.decisionIntegrityVersion()).thenReturn((short) 3);
        when(decision.selectedCatalogEntryId()).thenReturn(entry.id());
        when(decision.chatTurnId()).thenReturn(TURN);
        when(decision.organizationId()).thenReturn(ORG);
        when(decision.selectedProvider()).thenReturn("openai");
        when(decision.selectedProviderModelId()).thenReturn("gpt-test");
        when(decision.selectedCatalogVersion()).thenReturn(entry.version());
        when(decision.selectedModelKey()).thenReturn(entry.modelKey());
        return decision;
    }

    private static AiTokenUsage plain() {
        return new AiTokenUsage(1_000, null, null, 500, false, true);
    }

    private static ModelCatalogEntry withCacheRatesAboveInput(ModelCatalogEntry b) {
        return new ModelCatalogEntry(b.id(), b.modelKey(), b.version(), b.provider(),
                b.providerModelId(), b.displayName(), b.lifecycle(), b.maxInputTokens(),
                b.maxOutputTokens(), b.capabilities(), b.inputModalities(), b.outputModalities(),
                b.retentionStatus(), b.retentionDays(), b.trainingUseStatus(), b.pricingStatus(),
                b.pricingComplete(), new BigDecimal("2"), new BigDecimal("0.5"),
                new BigDecimal("4"), new BigDecimal("8"), b.extraPricingJson(),
                b.pricingVersion(), b.effectiveFrom(), b.source(), b.createdByUserId(), b.createdAt());
    }
}
