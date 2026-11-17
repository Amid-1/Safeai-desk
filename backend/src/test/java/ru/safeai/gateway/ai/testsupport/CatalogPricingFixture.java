package ru.safeai.gateway.ai.testsupport;

import ru.safeai.gateway.ai.execution.ModelExecutionPlanEntity;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanRepository;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.ai.pricing.PricingResolver;
import ru.safeai.gateway.model.domain.*;
import ru.safeai.gateway.model.pricing.VersionedCatalogPricingResolver;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the real resolver with immutable, operation-bound catalog evidence. */
public final class CatalogPricingFixture {
    private CatalogPricingFixture() {}

    public record Price(String model, BigDecimal input, BigDecimal output,
                        String currency, String version) {}

    public static ModelCatalogEntry entry(String provider, Price price, Clock clock) {
        if (!"USD".equals(price.currency())) {
            throw new IllegalArgumentException("Fixture requires USD");
        }
        boolean free = price.input().signum() == 0 && price.output().signum() == 0;
        return new ModelCatalogEntry(UUID.randomUUID(), provider + ":" + price.model(),
                1, provider, price.model(), price.model(), ModelLifecycle.ACTIVE, 64_000, 2_048,
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED, null, ModelTrainingUseStatus.NOT_DECLARED,
                free ? ModelPricingStatus.FREE : ModelPricingStatus.CONFIGURED,
                true, price.input(), null, null, price.output(), "{}", price.version(),
                clock.instant(), ModelCatalogSource.RUNTIME_IMPORT, null, clock.instant(),
                ModelPricingScheme.FLAT_TOKEN_V1);
    }

    public static PricingResolver unpriced(Clock clock) {
        return new VersionedCatalogPricingResolver(mock(ModelExecutionPlanRepository.class),
                mock(ModelRouteDecisionRepository.class), mock(ModelCatalogRepository.class), clock);
    }

    public static PricingResolver resolver(String provider, String requestedModel,
                                          UUID operation, List<Price> prices, Clock clock) {
        var plans = mock(ModelExecutionPlanRepository.class);
        var decisions = mock(ModelRouteDecisionRepository.class);
        var catalog = mock(ModelCatalogRepository.class);
        Price selected = prices.stream().filter(p -> p.model().equals(requestedModel))
                .findFirst().orElse(null);
        if (selected != null) {
            ModelCatalogEntry entry = entry(provider, selected, clock);
            UUID turn = UUID.randomUUID(), org = UUID.randomUUID(), decisionId = UUID.randomUUID();
            when(plans.findByProviderOperationId(operation)).thenReturn(Optional.of(
                    ModelExecutionPlanEntity.create(operation, turn, org, decisionId,
                            requestedModel, clock.instant())));
            ModelRouteDecision decision = mock(ModelRouteDecision.class);
            when(decision.outcome()).thenReturn(ModelRouteOutcome.ALLOWED);
            when(decision.decisionIntegrityVersion()).thenReturn((short) 3);
            when(decision.selectedCatalogEntryId()).thenReturn(entry.id());
            when(decision.chatTurnId()).thenReturn(turn);
            when(decision.organizationId()).thenReturn(org);
            when(decision.selectedProvider()).thenReturn(provider);
            when(decision.selectedProviderModelId()).thenReturn(requestedModel);
            when(decision.selectedCatalogVersion()).thenReturn(entry.version());
            when(decision.selectedModelKey()).thenReturn(entry.modelKey());
            when(decisions.findById(decisionId)).thenReturn(Optional.of(decision));
            when(catalog.findById(entry.id())).thenReturn(Optional.of(entry));
        }
        return new VersionedCatalogPricingResolver(plans, decisions, catalog, clock);
    }
}
