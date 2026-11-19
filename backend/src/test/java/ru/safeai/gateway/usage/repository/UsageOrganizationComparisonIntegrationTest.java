package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageOrganizationSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
class UsageOrganizationComparisonIntegrationTest extends UsagePostgresIntegrationTestSupport {
    @Autowired
    private UsageQueryRepository repository;

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
        var onlyA = repository.findOrganizations(criteria(null, USER_A_ID, "comparison", RANGE_FROM, RANGE_TO),
                plan, PAGEABLE).getContent();
        var inactiveB = row(onlyA, ORGANIZATION_B_ID);
        assertThat(inactiveB.responses().assistantMessages()).isZero();
        assertThat(inactiveB.cost().knownCostUsd()).isNull();
    }

    private UsageOrganizationSummaryResponse row(
            java.util.List<UsageOrganizationSummaryResponse> rows, UUID id
    ) {
        return rows.stream().filter(r -> r.organizationId().equals(id)).findFirst().orElseThrow();
    }
}
