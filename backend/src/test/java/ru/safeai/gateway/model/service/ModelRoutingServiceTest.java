package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteRequestMutexRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRoutingServiceTest {

    @Mock
    private ModelCatalogRepository catalogRepository;

    @Mock
    private OrganizationModelPolicyRepository policyRepository;

    @Mock
    private ModelRouteDecisionRepository decisionRepository;

    @Mock
    private ModelRouteRequestMutexRepository requestMutexRepository;

    @Mock
    private RuntimeModelStatusService runtimeStatusService;

    @Mock
    private AuditEventService audit;

    private ModelRoutingService service;

    @BeforeEach
    void setUp() {
        service = new ModelRoutingService(
                catalogRepository,
                policyRepository,
                decisionRepository,
                requestMutexRepository,
                runtimeStatusService,
                audit,
                ModelTestFixtures.CLOCK
        );
    }

    @Test
    void runtimeSelectionUsesEffectiveCatalogAtDecisionInstant() {
        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();
        stubNewDecisionNoPolicy();
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(entry));

        var result = service.decide(
                request(null, Set.of()),
                ModelTestFixtures.userPrincipal()
        );

        assertThat(result.catalogEntryId())
                .isEqualTo(entry.id());
        assertThat(result.reason())
                .isEqualTo(ModelRouteReason.RUNTIME_ONLY_MATCH);

        ArgumentCaptor<ModelRouteDecision> captor =
                ArgumentCaptor.forClass(ModelRouteDecision.class);
        verify(decisionRepository).insert(captor.capture());
        assertThat(captor.getValue().outcome())
                .isEqualTo(ModelRouteOutcome.ALLOWED);
        assertThat(captor.getValue().chatTurnId())
                .isEqualTo(ModelTestFixtures.TURN_ID);
        assertThat(captor.getValue().decisionSha256())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void explicitSelectionUsesEffectiveVersionAtDecisionInstant() {
        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();
        stubNewDecisionNoPolicy();
        when(catalogRepository.findEffective(
                "openai:gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(Optional.of(entry));

        service.decide(
                request(" OpenAI:GPT-Test ", Set.of()),
                ModelTestFixtures.userPrincipal()
        );

        verify(catalogRepository).findEffective(
                "openai:gpt-test",
                ModelTestFixtures.NOW
        );
    }

    @Test
    void previouslyGovernedRuntimeCannotFallBackAfterEffectiveRemap() {
        stubNewDecisionNoPolicy();
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

        ModelRouteDeniedException exception =
                catchDenial(request(null, Set.of()));

        assertThat(exception.getReason())
                .isEqualTo(ModelRouteReason.RUNTIME_MISMATCH);

        ArgumentCaptor<ModelRouteDecision> captor =
                ArgumentCaptor.forClass(ModelRouteDecision.class);
        verify(decisionRepository).insert(captor.capture());
        assertThat(captor.getValue().chatTurnId())
                .isNull();
        assertThat(captor.getValue().outcome())
                .isEqualTo(ModelRouteOutcome.DENIED);
    }

    @Test
    void futureOnlyCatalogHistoryDoesNotDisableBootstrapFallbackEarly() {
        stubNewDecisionNoPolicy();
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

        var result = service.decide(
                request(null, Set.of()),
                ModelTestFixtures.userPrincipal()
        );

        assertThat(result.reason())
                .isEqualTo(ModelRouteReason.LEGACY_RUNTIME_FALLBACK);
        assertThat(result.modelKey())
                .isEqualTo("runtime:openai:gpt-test");
    }

    @Test
    void replayDoesNotReconsultMutableRuntimeOrPolicyState() {
        ModelRouteRequest request = request(
                "openai:gpt-test",
                Set.of()
        );
        ModelRouteDecision persisted =
                allowedDecision(request);
        when(decisionRepository.findByRequest(
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID
        )).thenReturn(Optional.of(persisted));

        var result = service.decide(
                request,
                ModelTestFixtures.userPrincipal()
        );

        assertThat(result.decisionId())
                .isEqualTo(persisted.id());
        verify(requestMutexRepository).lock(
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID
        );
        verify(runtimeStatusService, never()).current();
        verify(policyRepository, never()).findLatest(any());
        verify(decisionRepository, never())
                .insert(any());
    }

    @Test
    void sameClientRequestAndContentButDifferentRequestedModelIsConflict() {
        ModelRouteRequest original = request(
                "openai:gpt-test",
                Set.of()
        );
        when(decisionRepository.findByRequest(
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID
        )).thenReturn(Optional.of(allowedDecision(original)));

        assertThatThrownBy(() -> service.decide(
                request("openai:other", Set.of()),
                ModelTestFixtures.userPrincipal()
        )).isInstanceOf(ConflictException.class);

        verify(runtimeStatusService, never()).current();
    }

    @Test
    void sameClientRequestAndContentButDifferentCapabilitiesIsConflict() {
        ModelRouteRequest original = request(null, Set.of());
        when(decisionRepository.findByRequest(
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID
        )).thenReturn(Optional.of(allowedDecision(original)));

        assertThatThrownBy(() -> service.decide(
                request(null, Set.of(ModelCapability.VISION)),
                ModelTestFixtures.userPrincipal()
        )).isInstanceOf(ConflictException.class);
    }

    @Test
    void unsupportedCapabilityIsPersistedAsStableDenial() {
        stubNewDecisionNoPolicy();
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(ModelTestFixtures.freeEntry()));

        ModelRouteDeniedException exception = catchDenial(
                request(null, Set.of(ModelCapability.TOOLS))
        );

        assertThat(exception.getReason())
                .isEqualTo(ModelRouteReason.CAPABILITY_UNSUPPORTED);
    }

    @Test
    void tenantInputLimitIsEnforcedBeforeProviderReservation() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        null,
                        10,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT,
                        false,
                        false,
                        false
                );
        stubNewDecisionWithPolicy(tenantPolicy);
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(ModelTestFixtures.freeEntry()));

        ModelRouteDeniedException exception = catchDenial(
                request(null, Set.of())
        );

        assertThat(exception.getReason())
                .isEqualTo(ModelRouteReason.INPUT_LIMIT_EXCEEDED);
    }

    @Test
    void requireCompletePricingFailsClosedForIncompleteCatalog() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT,
                        true,
                        false,
                        false
                );
        ModelCatalogEntry incomplete = ModelTestFixtures.entry(
                ru.safeai.gateway.model.domain.ModelLifecycle.ACTIVE,
                ModelPricingStatus.INCOMPLETE,
                false,
                BigDecimal.ONE,
                new BigDecimal("4"),
                Set.of(),
                ru.safeai.gateway.model.domain.ModelRetentionStatus.NOT_DECLARED,
                ru.safeai.gateway.model.domain.ModelTrainingUseStatus.NOT_DECLARED
        );
        stubNewDecisionWithPolicy(tenantPolicy);
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(incomplete));

        ModelRouteDeniedException exception = catchDenial(
                request(null, Set.of())
        );

        assertThat(exception.getReason())
                .isEqualTo(ModelRouteReason.PRICING_INCOMPLETE);
    }

    @Test
    void maxRequestCostIsEnforcedUsingWorstCaseOutputLimit() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        null,
                        null,
                        null,
                        new BigDecimal("0.001000000000"),
                        null,
                        BudgetEnforcement.SOFT,
                        false,
                        false,
                        false
                );
        stubNewDecisionWithPolicy(tenantPolicy);
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(ModelTestFixtures.configuredEntry()));

        ModelRouteDeniedException exception = catchDenial(
                request(null, Set.of())
        );

        assertThat(exception.getReason())
                .isEqualTo(
                        ModelRouteReason.REQUEST_COST_LIMIT_EXCEEDED
                );
    }

    @Test
    void hardMonthlyBudgetFailsClosedWhenExistingCommitmentIsUnknown() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.hardPolicy();
        stubNewDecisionWithPolicy(tenantPolicy);
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(ModelTestFixtures.configuredEntry()));
        when(decisionRepository.loadCommittedMonthlyCostSnapshot(
                eq(ModelTestFixtures.ORGANIZATION_ID),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                        BigDecimal.ZERO,
                        1L
                )
        );

        ModelRouteDeniedException exception = catchDenial(
                request(null, Set.of())
        );

        assertThat(exception.getReason())
                .isEqualTo(
                        ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE
                );
    }

    @Test
    void hardMonthlyBudgetPersistsProjectedSpendEvidenceOnExceededDenial() {
        OrganizationModelPolicy tenantPolicy =
                ModelTestFixtures.policy(
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("1.000000000000"),
                        BudgetEnforcement.HARD,
                        false,
                        false,
                        false
                );
        stubNewDecisionWithPolicy(tenantPolicy);
        when(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).thenReturn(List.of(ModelTestFixtures.configuredEntry()));
        when(decisionRepository.loadCommittedMonthlyCostSnapshot(
                eq(ModelTestFixtures.ORGANIZATION_ID),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                        new BigDecimal("0.999000000000"),
                        0L
                )
        );

        ModelRouteDeniedException exception = catchDenial(
                request(null, Set.of())
        );
        assertThat(exception.getReason())
                .isEqualTo(ModelRouteReason.MONTHLY_BUDGET_EXCEEDED);

        ArgumentCaptor<ModelRouteDecision> captor =
                ArgumentCaptor.forClass(ModelRouteDecision.class);
        verify(decisionRepository).insert(captor.capture());
        assertThat(captor.getValue().budgetExceeded())
                .isTrue();
        assertThat(captor.getValue().monthlyProjectedUsd())
                .isGreaterThan(captor.getValue().monthlyBudgetUsd());
    }

    @Test
    void principalIdentityMismatchFailsBeforeRepositoryAccess() {
        SafeAiUserPrincipal otherTenantPrincipal =
                ModelTestFixtures.principal(
                        ModelTestFixtures.OTHER_ORGANIZATION_ID,
                        "ROLE_USER"
                );

        assertThatThrownBy(() -> service.decide(
                request(null, Set.of()),
                otherTenantPrincipal
        )).isInstanceOf(ForbiddenOperationException.class);

        verify(requestMutexRepository, never())
                .lock(any(), any());
        verify(decisionRepository, never())
                .findByRequest(any(), any());
    }

    @Test
    void tenantAdminCannotDiscoverCrossTenantDecisionById() {
        ModelRouteDecision decision = decisionForOrganization(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        );
        when(decisionRepository.findById(decision.id()))
                .thenReturn(Optional.of(decision));

        assertThatThrownBy(() -> service.findDecision(
                decision.id(),
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void superAdminCanReadCrossTenantDecisionWhenIntegrityIsValid() {
        ModelRouteDecision decision = decisionForOrganization(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        );
        when(decisionRepository.findById(decision.id()))
                .thenReturn(Optional.of(decision));

        var response = service.findDecision(
                decision.id(),
                ModelTestFixtures.superAdminPrincipal()
        );

        assertThat(response.id())
                .isEqualTo(decision.id());
        assertThat(response.organizationId())
                .isEqualTo(ModelTestFixtures.OTHER_ORGANIZATION_ID);
    }

    @Test
    void evidenceReadFailsClosedWhenPersistedHashDoesNotMatchSnapshot() {
        ModelRouteDecision valid = decisionForOrganization(
                ModelTestFixtures.ORGANIZATION_ID
        );
        ModelRouteDecision tampered = copyWithHash(
                valid,
                "0".repeat(64)
        );
        when(decisionRepository.findById(tampered.id()))
                .thenReturn(Optional.of(tampered));

        assertThatThrownBy(() -> service.findDecision(
                tampered.id(),
                ModelTestFixtures.adminPrincipal()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity check failed");
    }

    @Test
    void allowedDecisionWritesAuditEvidence() {
        stubNewDecisionNoPolicy();

        when(
                catalogRepository.findEffectiveByRuntime(
                        "openai",
                        "gpt-test",
                        ModelTestFixtures.NOW
                )
        ).thenReturn(
                List.of(
                        ModelTestFixtures.freeEntry()
                )
        );

        SafeAiUserPrincipal principal =
                ModelTestFixtures.userPrincipal();

        service.decide(
                request(
                        null,
                        Set.of()
                ),
                principal
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor =
                ArgumentCaptor.forClass(
                        Map.class
                );

        verify(
                audit
        ).record(
                same(principal),
                eq(
                        ModelTestFixtures.ORGANIZATION_ID
                ),
                eq(
                        AuditEventType.MODEL_ROUTE_DECIDED
                ),
                detailsCaptor.capture()
        );

        assertThat(
                detailsCaptor.getValue()
        ).containsKeys(
                "decisionId",
                "chatTurnId",
                "decisionIntegrityVersion",
                "decisionSha256",
                "inputAccountingVersion",
                "additionalInputUnitUpperBound",
                "outcome",
                "reason",
                "modelKey",
                "provider",
                "providerModel",
                "catalogVersion",
                "estimatedMaxCostUsd",
                "budgetExceeded",
                "monthlyCostKnown",
                "monthlyCostState"
        );

        assertThat(
                detailsCaptor.getValue()
                        .get("decisionIntegrityVersion")
        ).isEqualTo(
                (short) 3
        );

        assertThat(
                detailsCaptor.getValue()
                        .get("inputAccountingVersion")
        ).isEqualTo(
                ru.safeai.gateway.ai.input.AiInputUnitEstimator.VERSION
        );

        assertThat(
                detailsCaptor.getValue()
                        .get("additionalInputUnitUpperBound")
        ).isEqualTo(
                0L
        );

        assertThat(
                detailsCaptor.getValue()
                        .get("monthlyCostState")
        ).isEqualTo(
                ru.safeai.gateway.model.domain.MonthlyCostState.NOT_EVALUATED
        );

        assertThat(
                detailsCaptor.getValue()
                        .get("monthlyCostKnown")
        ).isEqualTo(
                false
        );
    }

    private void stubNewDecisionNoPolicy() {
        when(decisionRepository.findByRequest(
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID
        )).thenReturn(Optional.empty());
        when(runtimeStatusService.current())
                .thenReturn(ModelTestFixtures.freeRuntime());
        when(policyRepository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.empty());
    }

    private void stubNewDecisionWithPolicy(
            OrganizationModelPolicy tenantPolicy
    ) {
        when(decisionRepository.findByRequest(
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID
        )).thenReturn(Optional.empty());
        when(runtimeStatusService.current())
                .thenReturn(ModelTestFixtures.configuredRuntime());
        when(policyRepository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(tenantPolicy));
    }

    private ModelRouteDeniedException catchDenial(
            ModelRouteRequest routeRequest
    ) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                ModelRouteDeniedException.class,
                () -> service.decide(
                        routeRequest,
                        ModelTestFixtures.userPrincipal()
                )
        );
    }

    private static ModelRouteRequest request(
            String requestedModelKey,
            Set<ModelCapability> capabilities
    ) {
        return ModelTestFixtures.routeRequest(
                requestedModelKey,
                capabilities,
                0L
        );
    }

    private static ModelRouteDecision allowedDecision(
            ModelRouteRequest request
    ) {
        ModelRouteDecisionFactory factory =
                new ModelRouteDecisionFactory();
        return factory.buildDecision(
                request,
                null,
                new ModelRouteDecisionFactory.DecisionDraft(
                        ModelTestFixtures.freeEntry(),
                        "openai:gpt-test",
                        "openai",
                        "gpt-test",
                        13L,
                        4_096L,
                        BigDecimal.ZERO,
                        true,
                        ModelRoutingCostPolicy.BudgetSnapshot.none(),
                        ModelRouteOutcome.ALLOWED,
                        ModelRouteReason.RUNTIME_ONLY_MATCH
                ),
                request.plannedTurnId(),
                ModelTestFixtures.NOW
        );
    }

    private static ModelRouteDecision decisionForOrganization(
            UUID organizationId
    ) {
        ModelRouteDecision base = allowedDecision(
                request(null, Set.of())
        );
        ModelRouteDecision changed = new ModelRouteDecision(
                base.id(),
                organizationId,
                base.userId(),
                base.chatId(),
                base.chatTurnId(),
                base.clientRequestId(),
                base.requestContentHash(),
                base.requestedModelKey(),
                base.selectedCatalogEntryId(),
                base.selectedCatalogVersion(),
                base.selectedModelKey(),
                base.selectedProvider(),
                base.selectedProviderModelId(),
                base.policyId(),
                base.policyVersion(),
                base.requiredCapabilities(),
                base.inputAccountingVersion(),
                base.additionalInputUnitUpperBound(),
                base.estimatedInputTokens(),
                base.estimatedOutputTokens(),
                base.estimatedMaxCostUsd(),
                base.monthlyBudgetUsd(),
                base.monthlySpentUsd(),
                base.monthlyProjectedUsd(),
                base.monthlyCostKnown(),
                base.budgetEnforcement(),
                base.budgetExceeded(),
                base.pricingComplete(),
                base.outcome(),
                base.reason(),
                base.decisionIntegrityVersion(),
                "",
                base.createdAt()
        );
        return ModelRouteDecisionIntegrity.seal(changed);
    }

    private static ModelRouteDecision copyWithHash(
            ModelRouteDecision source,
            String hash
    ) {
        return new ModelRouteDecision(
                source.id(),
                source.organizationId(),
                source.userId(),
                source.chatId(),
                source.chatTurnId(),
                source.clientRequestId(),
                source.requestContentHash(),
                source.requestedModelKey(),
                source.selectedCatalogEntryId(),
                source.selectedCatalogVersion(),
                source.selectedModelKey(),
                source.selectedProvider(),
                source.selectedProviderModelId(),
                source.policyId(),
                source.policyVersion(),
                source.requiredCapabilities(),
                source.inputAccountingVersion(),
                source.additionalInputUnitUpperBound(),
                source.estimatedInputTokens(),
                source.estimatedOutputTokens(),
                source.estimatedMaxCostUsd(),
                source.monthlyBudgetUsd(),
                source.monthlySpentUsd(),
                source.monthlyProjectedUsd(),
                source.monthlyCostKnown(),
                source.budgetEnforcement(),
                source.budgetExceeded(),
                source.pricingComplete(),
                source.outcome(),
                source.reason(),
                source.decisionIntegrityVersion(),
                hash,
                source.createdAt()
        );
    }
}
