package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.budget.PlatformBudgetClassification.Status;
import ru.safeai.gateway.model.service.OrganizationBudgetStatusService;
import ru.safeai.gateway.model.service.OrganizationModelPolicyService;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.usage.dto.UsageOrganizationSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
class UsageOrganizationComparisonIntegrationTest extends UsagePostgresIntegrationTestSupport {
    @Autowired
    private UsageQueryRepository repository;

    @Autowired
    private OrganizationBudgetStatusService budgetStatus;

    @Autowired
    private OrganizationModelPolicyService policyService;

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void readsRealMonthlyLedgerEvenWhenOrganizationHasNoUsage() {
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(USER_A_ID, ORGANIZATION_A_ID, 0, 0,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        var result = budgetStatus.current(ORGANIZATION_B_ID, principal);
        assertThat(result.organizationId()).isEqualTo(ORGANIZATION_B_ID);
        assertThat(new BigDecimal(result.knownActualUsd())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(new BigDecimal(result.reservedUsd())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.unknownCommittedCostCount()).isZero();
        assertThat(result.status()).isEqualTo("UNCONFIGURED");
        assertThat(result.monthlyBudgetUsd()).isNull();
        assertThat(result.remainingUsd()).isNull();
        assertThat(result.asOf()).isBetween(result.periodStart(), result.periodEnd());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void realBudgetServiceRejectsCrossTenantAdmin() {
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(USER_A_ID, ORGANIZATION_A_ID, 0, 0,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThatThrownBy(() -> budgetStatus.current(ORGANIZATION_B_ID, principal))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> budgetStatus.platformSummary(principal))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void groupsAcrossTenantsWithExactKnownCostAndRetainsInactiveDirectoryRows() {
        var at = Instant.parse("2026-06-15T12:00:00Z");
        insertCompletedAssistant(SESSION_A_ID, ORGANIZATION_A_ID, "comparison", "COMPLETED",
                10, 20, "AVAILABLE", new BigDecimal("0.000000000001"), "PRICED", at);
        insertCompletedAssistant(SESSION_A_ID, ORGANIZATION_A_ID, "comparison", "COMPLETED",
                1, 2, "AVAILABLE", null, "UNPRICED", at.plusSeconds(1));
        insertCompletedAssistant(SESSION_B_ID, ORGANIZATION_B_ID, "comparison", "COMPLETED",
                5, 5, "AVAILABLE", null, "UNPRICED", at);
        var query = criteria(null, null, "comparison", RANGE_FROM, RANGE_TO);
        var plan = livePlan(RANGE_FROM, RANGE_TO);
        var rows = repository.findOrganizations(query, plan, PAGEABLE).getContent();
        var a = row(rows, ORGANIZATION_A_ID);
        var b = row(rows, ORGANIZATION_B_ID);
        assertThat(a.usage().confirmedTotalTokens()).isEqualTo(33);
        assertThat(a.cost().knownCostUsd()).isEqualTo("0.000000000001");
        assertThat(a.cost().pricingComplete()).isFalse();
        assertThat(b.usage().confirmedTotalTokens()).isEqualTo(10);
        assertThat(b.cost().knownCostUsd()).isNull();
        assertThat(repository.findOrganizations(query, plan, PageRequest.of(0, 1)).hasNext()).isTrue();
        var ranked = repository.findOrganizations(query, plan, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "tokens")));
        assertThat(ranked.getContent()).extracting(UsageOrganizationSummaryResponse::organizationId).containsExactly(ORGANIZATION_A_ID);
        var second = repository.findOrganizations(query, plan, PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "tokens")));
        assertThat(second.getContent()).extracting(UsageOrganizationSummaryResponse::organizationId).containsExactly(ORGANIZATION_B_ID);
        var byCost = repository.findOrganizations(query, plan, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "cost")));
        assertThat(byCost.getContent()).extracting(UsageOrganizationSummaryResponse::organizationId).containsExactly(ORGANIZATION_A_ID);
        var onlyA = repository.findOrganizations(criteria(null, USER_A_ID, "comparison", RANGE_FROM, RANGE_TO),
                plan, PAGEABLE).getContent();
        var inactiveB = row(onlyA, ORGANIZATION_B_ID);
        assertThat(inactiveB.responses().assistantMessages()).isZero();
        assertThat(inactiveB.cost().knownCostUsd()).isNull();
        var used = new UsageQueryCriteria(RANGE_FROM, RANGE_TO, null, null, "comparison", null, true);
        assertThat(repository.findOrganizations(used, plan, PAGEABLE).getContent())
                .extracting(UsageOrganizationSummaryResponse::organizationId)
                .containsExactlyInAnyOrder(ORGANIZATION_A_ID, ORGANIZATION_B_ID);
        var name = b.organizationName();
        var search = new UsageQueryCriteria(RANGE_FROM, RANGE_TO, null, USER_A_ID, "comparison",
                name.toUpperCase(java.util.Locale.ROOT), false);
        assertThat(repository.findOrganizations(search, plan, PAGEABLE).getContent())
                .extracting(UsageOrganizationSummaryResponse::organizationId).contains(ORGANIZATION_B_ID);
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void platformBudgetSummaryCountsAllOrganizationsEvenWithoutUsage() {
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(USER_A_ID, ORGANIZATION_A_ID, 0, 0,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        var summary = budgetStatus.platformSummary(principal);
        assertThat(summary.total()).isGreaterThanOrEqualTo(2);
        assertThat(summary.unconfigured()).isGreaterThanOrEqualTo(1);
        assertThat(summary.asOf()).isBetween(summary.periodStart(), summary.periodEnd());
        assertThat(summary.within() + summary.nearLimit() + summary.exceeded() + summary.noLimit()
                + summary.unknown() + summary.disabled() + summary.unconfigured()).isEqualTo(summary.total());
        policyService.createVersion(ORGANIZATION_B_ID, new CreateOrganizationModelPolicyVersionRequest(
                0, true, java.util.Set.of(), java.util.Set.of(), null, null, null, null,
                BigDecimal.ZERO, BudgetEnforcement.SOFT, false, false, false), principal);
        var after = budgetStatus.platformSummary(principal);
        assertThat(after.total()).isEqualTo(summary.total());
        assertThat(after.unconfigured()).isEqualTo(summary.unconfigured() - 1);
        assertThat(after.nearLimit()).isEqualTo(summary.nearLimit() + 1);
        policyService.createVersion(ORGANIZATION_B_ID, new CreateOrganizationModelPolicyVersionRequest(
                1, true, java.util.Set.of(), java.util.Set.of(), null, null, null, null,
                null, BudgetEnforcement.SOFT, false, false, false), principal);
        var unlimited = budgetStatus.platformSummary(principal);
        assertThat(unlimited.nearLimit()).isEqualTo(summary.nearLimit());
        assertThat(unlimited.noLimit()).isEqualTo(summary.noLimit() + 1);
        var counts = java.util.Map.of(
                Status.WITHIN, unlimited.within(), Status.NEAR, unlimited.nearLimit(),
                Status.EXCEEDED, unlimited.exceeded(), Status.NO_LIMIT, unlimited.noLimit(),
                Status.UNKNOWN, unlimited.unknown(), Status.DISABLED, unlimited.disabled(),
                Status.UNCONFIGURED, unlimited.unconfigured());
        for (var entry : counts.entrySet()) {
            var filter = new UsageQueryCriteria(RANGE_FROM, RANGE_TO, null, null, "unused-model", null, false,
                    new UsageQueryCriteria.BudgetFilter(entry.getKey(), unlimited.periodStart(), unlimited.periodEnd()));
            var ids = new java.util.HashSet<UUID>();
            int page = 0;
            boolean next;
            do {
                var slice = repository.findOrganizations(filter, livePlan(RANGE_FROM, RANGE_TO),
                        PageRequest.of(page++, 1, Sort.by(Sort.Direction.DESC, "tokens")));
                for (var row : slice.getContent()) assertThat(ids.add(row.organizationId())).isTrue();
                next = slice.hasNext();
            } while (next);
            assertThat((long) ids.size()).isEqualTo(entry.getValue());
            if (entry.getKey() == Status.NO_LIMIT) assertThat(ids).contains(ORGANIZATION_B_ID);
        }
    }

    private UsageOrganizationSummaryResponse row(
            java.util.List<UsageOrganizationSummaryResponse> rows, UUID id
    ) {
        return rows.stream().filter(r -> r.organizationId().equals(id)).findFirst().orElseThrow();
    }
}
