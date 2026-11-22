package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.controller.OrganizationBudgetStatusController;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.dto.OrganizationBudgetStatusResponse;
import ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrganizationBudgetStatusSecurityTest {

    @Test
    void tenantAndRoleDenialsPrecedeAllFinancialReads() {
        var policyRepository = mock(OrganizationModelPolicyRepository.class);
        var ledger = mock(ModelRouteDecisionRepository.class);
        var policies = new OrganizationModelPolicyService(
                policyRepository, mock(AuditEventService.class), ModelTestFixtures.CLOCK,
                mock(ModelPolicyPreviewConfirmationService.class));
        var service = new OrganizationBudgetStatusService(policies, ledger, ModelTestFixtures.CLOCK);

        assertThatThrownBy(() -> service.current(ModelTestFixtures.OTHER_ORGANIZATION_ID,
                ModelTestFixtures.adminPrincipal())).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.current(ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.userPrincipal())).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.platformSummary(ModelTestFixtures.adminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.platformSummary(ModelTestFixtures.userPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.platformSummary(null))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(policyRepository, ledger);
    }

    @Test
    void unknownCommitmentsNeverBecomeAvailableBudget() {
        var policy = mock(OrganizationModelPolicyResponse.class);
        when(policy.organizationId()).thenReturn(ModelTestFixtures.ORGANIZATION_ID);
        when(policy.enabled()).thenReturn(true);
        when(policy.budgetEnforcement()).thenReturn(BudgetEnforcement.HARD);
        when(policy.monthlyBudgetUsd()).thenReturn("1.00");

        var from = Instant.parse("2026-08-01T00:00:00Z");
        var to = Instant.parse("2026-09-01T00:00:00Z");
        var unknown = OrganizationBudgetStatusResponse.from(policy,
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(new BigDecimal("0.20"), 1),
                from, to, ModelTestFixtures.NOW);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.remainingUsd()).isNull();
        assertThat(unknown.unknownCommittedCostCount()).isEqualTo(1);
        var exceeded = OrganizationBudgetStatusResponse.from(policy,
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(new BigDecimal("1.20"), 1),
                from, to, ModelTestFixtures.NOW);
        assertThat(exceeded.status()).isEqualTo("EXCEEDED_LOWER_BOUND");
        assertThat(exceeded.remainingUsd()).isNull();
        assertThat(exceeded.knownCommittedUsd()).isEqualTo("1.20");
        var known = OrganizationBudgetStatusResponse.from(policy,
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(new BigDecimal("0.20"), 0),
                from, to, ModelTestFixtures.NOW);
        assertThat(known.status()).isEqualTo("KNOWN");
        assertThat(known.remainingUsd()).isEqualTo("0.80");
    }

    @Test
    void httpAndServiceRetainAdministrativeAuthorizationContracts() throws NoSuchMethodException {
        assertThat(OrganizationBudgetStatusController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
        assertThat(OrganizationBudgetStatusController.class.getMethod("platformSummary",
                        SafeAiUserPrincipal.class).getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('SUPER_ADMIN')");
        assertThat(OrganizationBudgetStatusService.class.getMethod("platformSummary",
                        SafeAiUserPrincipal.class).getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('SUPER_ADMIN')");
        assertThat(OrganizationBudgetStatusService.class.getMethod("current", UUID.class,
                        SafeAiUserPrincipal.class).getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
