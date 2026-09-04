package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRoutingSelectionPolicyTest {

    @Mock
    private ModelCatalogRepository catalogRepository;

    private ModelRoutingSelectionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ModelRoutingSelectionPolicy(
                catalogRepository
        );
    }

    @Test
    void explicitRequestUsesEffectiveCatalogSnapshotAtDecisionInstant() {
        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();
        when(catalogRepository.findEffective(
                "openai:gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(Optional.of(entry));

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(
                        " OpenAI:GPT-Test ",
                        Set.of(),
                        0L
                ),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                ModelTestFixtures.NOW
        );

        assertThat(selection.entry())
                .isSameAs(entry);
        assertThat(selection.modelKey())
                .isEqualTo("openai:gpt-test");
        assertThat(selection.denialReason())
                .isNull();
    }

    @Test
    void policyDefaultIsUsedWhenRequestDoesNotSelectModel() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        "openai:gpt-test",
                        null,
                        null,
                        null,
                        null,
                        ru.safeai.gateway.model.domain.BudgetEnforcement.SOFT,
                        false,
                        false,
                        false
                );
        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();

        when(catalogRepository.findEffective(
                "openai:gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(Optional.of(entry));

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(
                        null,
                        Set.of(),
                        0L
                ),
                ModelTestFixtures.freeRuntime(),
                tenantPolicy,
                true,
                ModelTestFixtures.NOW
        );

        assertThat(selection.entry())
                .isSameAs(entry);
        assertThat(selection.allowedReason())
                .isEqualTo(ModelRouteReason.POLICY_DEFAULT);
    }

    @Test
    void runtimeCandidateIsSelectedDeterministicallyByModelKey() {
        ModelCatalogEntry laterAlphabetically = copyWithModelKey(
                ModelTestFixtures.freeEntry(),
                "z:model"
        );
        ModelCatalogEntry earlierAlphabetically = copyWithModelKey(
                ModelTestFixtures.freeEntry(),
                "a:model"
        );

        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(
                List.of(
                        laterAlphabetically,
                        earlierAlphabetically
                )
        );

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(
                        null,
                        Set.of(),
                        0L
                ),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                ModelTestFixtures.NOW
        );

        assertThat(selection.modelKey())
                .isEqualTo("a:model");
        assertThat(selection.allowedReason())
                .isEqualTo(ModelRouteReason.RUNTIME_ONLY_MATCH);
    }

    @Test
    void currentDisabledRuntimeCandidateIsKeptForExplicitDenial() {
        ModelCatalogEntry disabled = ModelTestFixtures.entry(
                ModelLifecycle.DISABLED,
                ru.safeai.gateway.model.domain.ModelPricingStatus.FREE,
                true,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                Set.of(),
                ModelRetentionStatus.NOT_DECLARED,
                ModelTrainingUseStatus.NOT_DECLARED
        );

        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(disabled));

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(null, Set.of(), 0L),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                ModelTestFixtures.NOW
        );

        assertThat(selection.entry())
                .isSameAs(disabled);
        assertThat(policy.validateCatalogAndPolicy(
                disabled,
                disabled.modelKey(),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                Set.of()
        )).isEqualTo(ModelRouteReason.MODEL_DISABLED);
    }

    @Test
    void governedRuntimeIdentityCannotReturnToLegacyFallback() {
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of());
        when(catalogRepository.hasEffectiveHistoryByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(true);

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(null, Set.of(), 0L),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                ModelTestFixtures.NOW
        );

        assertThat(selection.denialReason())
                .isEqualTo(ModelRouteReason.RUNTIME_MISMATCH);
        verify(catalogRepository)
                .hasEffectiveHistoryByRuntime(
                        "openai",
                        "gpt-test",
                        ModelTestFixtures.NOW
                );
    }

    @Test
    void futureOnlyHistoryKeepsBootstrapCompatibilityUntilActivation() {
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of());
        when(catalogRepository.hasEffectiveHistoryByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(false);

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(null, Set.of(), 0L),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                ModelTestFixtures.NOW
        );

        assertThat(selection.entry())
                .isNull();
        assertThat(selection.modelKey())
                .isEqualTo("runtime:openai:gpt-test");
        assertThat(selection.allowedReason())
                .isEqualTo(ModelRouteReason.LEGACY_RUNTIME_FALLBACK);
    }

    @Test
    void enabledPolicyWithoutMatchingCatalogNeverUsesLegacyFallback() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        ru.safeai.gateway.model.domain.BudgetEnforcement.SOFT,
                        false,
                        false,
                        false
                );

        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of());

        var selection = policy.selectCatalog(
                ModelTestFixtures.routeRequest(null, Set.of(), 0L),
                ModelTestFixtures.freeRuntime(),
                tenantPolicy,
                true,
                ModelTestFixtures.NOW
        );

        assertThat(selection.denialReason())
                .isEqualTo(ModelRouteReason.MODEL_NOT_FOUND);
    }

    @Test
    void denyListTakesPrecedenceOverAllowList() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of("openai:gpt-test"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        ru.safeai.gateway.model.domain.BudgetEnforcement.SOFT,
                        false,
                        false,
                        false
                );

        assertThat(policy.validateCatalogAndPolicy(
                ModelTestFixtures.freeEntry(),
                "openai:gpt-test",
                ModelTestFixtures.freeRuntime(),
                tenantPolicy,
                true,
                Set.of()
        )).isEqualTo(ModelRouteReason.MODEL_DENIED);
    }

    @Test
    void requiredCapabilityMustExistInCatalogAndRuntime() {
        ModelCatalogEntry entry = ModelTestFixtures.entry(
                ModelLifecycle.ACTIVE,
                ru.safeai.gateway.model.domain.ModelPricingStatus.FREE,
                true,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                Set.of(ModelCapability.TOOLS),
                ModelRetentionStatus.NOT_DECLARED,
                ModelTrainingUseStatus.NOT_DECLARED
        );

        assertThat(policy.validateCatalogAndPolicy(
                entry,
                entry.modelKey(),
                ModelTestFixtures.freeRuntime(),
                null,
                false,
                Set.of(ModelCapability.TOOLS)
        )).isEqualTo(ModelRouteReason.CAPABILITY_UNSUPPORTED);
    }

    @Test
    void noTrainingAndZeroRetentionPoliciesFailClosedOnUnknownDeclarations() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        ru.safeai.gateway.model.domain.BudgetEnforcement.SOFT,
                        false,
                        true,
                        true
                );

        assertThat(policy.validateCatalogAndPolicy(
                ModelTestFixtures.freeEntry(),
                "openai:gpt-test",
                ModelTestFixtures.freeRuntime(),
                tenantPolicy,
                true,
                Set.of()
        )).isEqualTo(ModelRouteReason.TRAINING_POLICY_UNSATISFIED);
    }

    private static ModelCatalogEntry copyWithModelKey(
            ModelCatalogEntry source,
            String modelKey
    ) {
        return new ModelCatalogEntry(
                java.util.UUID.randomUUID(),
                modelKey,
                source.version(),
                source.provider(),
                source.providerModelId(),
                source.displayName(),
                source.lifecycle(),
                source.maxInputTokens(),
                source.maxOutputTokens(),
                source.capabilities(),
                source.inputModalities(),
                source.outputModalities(),
                source.retentionStatus(),
                source.retentionDays(),
                source.trainingUseStatus(),
                source.pricingStatus(),
                source.pricingComplete(),
                source.inputUsdPer1mTokens(),
                source.cachedInputUsdPer1mTokens(),
                source.cacheWriteInputUsdPer1mTokens(),
                source.outputUsdPer1mTokens(),
                source.extraPricingJson(),
                source.pricingVersion(),
                source.effectiveFrom(),
                source.source(),
                source.createdByUserId(),
                source.createdAt()
        );
    }
}
