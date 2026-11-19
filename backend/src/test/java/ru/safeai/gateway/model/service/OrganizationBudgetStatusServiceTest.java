package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository.MonthlyCostSnapshot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganizationBudgetStatusServiceTest {
    private final UUID org = UUID.randomUUID();
    private final SafeAiUserPrincipal principal = mock(SafeAiUserPrincipal.class);
    private final OrganizationModelPolicyService policies = mock(OrganizationModelPolicyService.class);
    private final ModelRouteDecisionRepository decisions = mock(ModelRouteDecisionRepository.class);
    private final Instant start = Instant.parse("2026-11-01T00:00:00Z");
    private final Instant end = Instant.parse("2026-12-01T00:00:00Z");
    private final Instant now = Instant.parse("2026-11-19T14:00:00Z");
    private final OrganizationBudgetStatusService service = new OrganizationBudgetStatusService(
            policies, decisions, Clock.fixed(now, ZoneOffset.UTC));

    private void policy(boolean enabled, String budget) {
        when(policies.current(org, principal)).thenReturn(new OrganizationModelPolicyResponse(true,
                UUID.randomUUID(), org, 3, enabled, Set.of(), Set.of(), null, null, null, null,
                budget, BudgetEnforcement.HARD, false, false, false, null, now, null));
    }

    private void ledger(String actual, String reserved, long unknown) {
        BigDecimal a = new BigDecimal(actual);
        BigDecimal r = new BigDecimal(reserved);
        when(decisions.loadCommittedMonthlyCostSnapshot(org, start, end))
                .thenReturn(new MonthlyCostSnapshot(a.add(r), unknown, a, r));
    }

    @Test
    void usesCurrentUtcMonthAndSubtractsActualAndReservedExactly() {
        policy(true, "10.000000000001");
        ledger("1.000000000001", "2", 0);
        var result = service.current(org, principal);
        assertEquals("KNOWN", result.status());
        assertEquals(0, new BigDecimal("7").compareTo(new BigDecimal(result.remainingUsd())));
        assertEquals("3.000000000001", result.knownCommittedUsd());
        assertEquals(start, result.periodStart());
        assertEquals(end, result.periodEnd());
        verify(decisions).loadCommittedMonthlyCostSnapshot(org, start, end);
    }

    @Test
    void unknownCommitmentNeverProducesAnExactRemainingAmount() {
        policy(true, "10");
        ledger("2", "1", 1);
        assertEquals("UNKNOWN", service.current(org, principal).status());
        assertNull(service.current(org, principal).remainingUsd());
        ledger("10", "1", 1);
        assertEquals("EXCEEDED_LOWER_BOUND", service.current(org, principal).status());
        assertNull(service.current(org, principal).remainingUsd());
    }

    @Test
    void distinguishesExcessZeroLimitDisabledAndUnconfigured() {
        ledger("2", "1", 0);
        policy(true, "0");
        assertEquals("EXCEEDED", service.current(org, principal).status());
        assertEquals("0", service.current(org, principal).remainingUsd());
        policy(true, null);
        assertEquals("NO_LIMIT", service.current(org, principal).status());
        assertNull(service.current(org, principal).remainingUsd());
        policy(false, "100");
        assertEquals("DISABLED", service.current(org, principal).status());
        assertNull(service.current(org, principal).remainingUsd());
        when(policies.current(org, principal)).thenReturn(OrganizationModelPolicyResponse.unconfigured(org));
        assertEquals("UNCONFIGURED", service.current(org, principal).status());
    }

    @Test
    void policyAccessFailurePreventsAnyLedgerRead() {
        when(policies.current(org, principal)).thenThrow(new SecurityException("denied"));
        assertThrows(SecurityException.class, () -> service.current(org, principal));
        verifyNoInteractions(decisions);
    }
}
