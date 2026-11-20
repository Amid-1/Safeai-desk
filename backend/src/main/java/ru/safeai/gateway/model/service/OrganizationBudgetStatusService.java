package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.OrganizationBudgetStatusResponse;
import ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse;
import ru.safeai.gateway.model.dto.PlatformBudgetSummaryResponse;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class OrganizationBudgetStatusService {
    private final OrganizationModelPolicyService policies;
    private final ModelRouteDecisionRepository decisions;
    private final Clock clock;

    public OrganizationBudgetStatusService(
            OrganizationModelPolicyService policies, ModelRouteDecisionRepository decisions, Clock clock
    ) {
        this.policies = policies;
        this.decisions = decisions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public OrganizationBudgetStatusResponse current(UUID organizationId, SafeAiUserPrincipal principal) {
        // The existing policy reader enforces role, tenant and existence before the ledger is read.
        OrganizationModelPolicyResponse policy = policies.current(organizationId, principal);
        Instant now = clock.instant();
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return OrganizationBudgetStatusResponse.from(policy,
                decisions.loadCommittedMonthlyCostSnapshot(policy.organizationId(), start, end),
                start, end, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PlatformBudgetSummaryResponse platformSummary(SafeAiUserPrincipal principal) {
        if (principal == null || principal.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_SUPER_ADMIN".equals(authority.getAuthority()))) {
            throw new ForbiddenOperationException("Сводка бюджетов платформы недоступна");
        }
        Instant now = clock.instant();
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        return decisions.loadPlatformBudgetSummary(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(), now);
    }
}
