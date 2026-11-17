package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.execution.*;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.ProviderDataEvidence;
import ru.safeai.gateway.model.domain.*;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.*;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderDataDispatchGuardTest {
    @Test
    void dispatchRechecksBothPolicyVersionsAndEffectiveCatalogueOnEachAttempt() {
        var decisions = mock(ModelRouteDecisionRepository.class);
        var policies = mock(OrganizationModelPolicyRepository.class);
        var catalog = mock(ModelCatalogRepository.class);
        var runtime = mock(RuntimeModelStatusService.class);
        var clock = Clock.fixed(ModelTestFixtures.NOW, ZoneOffset.UTC);
        var guard = new ProviderDataDispatchGuard(decisions, policies, catalog, runtime, clock);
        var request = mock(AiExecutionRequest.class);
        var identity = mock(AiExecutionIdentity.class);
        var decision = mock(ModelRouteDecision.class);
        UUID decisionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        var org = ModelTestFixtures.ORGANIZATION_ID;
        var original = policy(false, true);
        var latest = policy(true, false);
        var entry = ModelTestFixtures.entry(ModelLifecycle.ACTIVE, ModelPricingStatus.FREE,
                true, BigDecimal.ZERO, BigDecimal.ZERO, Set.of(),
                ModelRetentionStatus.ZERO_DATA_RETENTION, ModelTrainingUseStatus.NOT_USED);
        when(request.modelRouteDecisionId()).thenReturn(decisionId);
        when(request.executionIdentity()).thenReturn(identity);
        when(identity.organizationId()).thenReturn(org);
        when(request.chatTurnId()).thenReturn(turnId);
        when(request.target()).thenReturn(ProviderExecutionTarget.staticTarget("openai", "gpt-test"));
        when(decisions.findById(decisionId)).thenReturn(Optional.of(decision));
        when(decision.organizationId()).thenReturn(org);
        when(decision.chatTurnId()).thenReturn(turnId);
        when(decision.outcome()).thenReturn(ModelRouteOutcome.ALLOWED);
        when(decision.policyId()).thenReturn(original.id());
        when(decision.selectedCatalogEntryId()).thenReturn(entry.id());
        when(decision.selectedModelKey()).thenReturn(entry.modelKey());
        when(policies.findById(org, original.id())).thenReturn(Optional.of(original));
        when(policies.findLatest(org)).thenReturn(Optional.of(latest));
        when(catalog.findById(entry.id())).thenReturn(Optional.of(entry));
        when(catalog.findEffective(entry.modelKey(), clock.instant())).thenReturn(Optional.of(entry));

        when(runtime.current()).thenReturn(status(true, true));
        assertDoesNotThrow(() -> guard.require(request, false));
        assertDoesNotThrow(() -> guard.require(request, true));

        when(runtime.current()).thenReturn(status(true, false));
        assertThrows(AiOrganizationExecutionDeniedException.class, () -> guard.require(request, false));
        var denied = assertThrows(AiProviderException.class, () -> guard.require(request, true));
        assertFalse(denied.isRetryable());
        assertFalse(denied.isOutcomeAmbiguous());

        when(runtime.current()).thenReturn(status(false, true));
        assertThrows(AiOrganizationExecutionDeniedException.class, () -> guard.require(request, false));
        when(runtime.current()).thenReturn(status(true, true));
        when(catalog.findEffective(entry.modelKey(), clock.instant())).thenReturn(Optional.empty());
        assertThrows(AiOrganizationExecutionDeniedException.class, () -> guard.require(request, false));
        when(policies.findById(org, original.id())).thenReturn(Optional.empty());
        assertThrows(AiOrganizationExecutionDeniedException.class, () -> guard.require(request, false));
    }

    private static OrganizationModelPolicy policy(boolean training, boolean retention) {
        return ModelTestFixtures.policy(true, Set.of(), Set.of(), null, null, null,
                null, null, BudgetEnforcement.SOFT, false, training, retention);
    }

    private static RuntimeModelStatusResponse status(boolean training, boolean retention) {
        return new RuntimeModelStatusResponse("openai", "gpt-test", true, "SINGLE_PROVIDER_STATIC",
                32000, 4096, false, false, false, "NOT_DECLARED", "NOT_PROBED", "FREE",
                BigDecimal.ZERO, BigDecimal.ZERO, null,
                new ProviderDataEvidence.Status(training, retention, "DOC-TEST", null, null));
    }
}
