package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UsageOrganizationFactsTest {
    @Test
    void bothRollupAndLiveUseHistoricalTenantAndApplyFiltersBeforeGrouping() {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-19T00:00:00Z");
        UUID user = UUID.randomUUID();
        var criteria = new UsageQueryCriteria(from, to, null, user, "mock");
        var plan = new UsageQueryPlan(LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-18"),
                List.of(new UsageInstantRange(Instant.parse("2026-11-18T00:00:00Z"), to)));
        var facts = new UsageFactQueryBuilder().organizationFacts(criteria, plan, false, true);
        assertTrue(facts.cte().contains("select r.organization_id"));
        assertTrue(facts.cte().contains("select m.organization_id"));
        assertTrue(facts.cte().contains(", m.organization_id"));
        assertTrue(facts.cte().contains("r.user_id = :userId"));
        assertTrue(facts.cte().contains("s.user_id = :userId"));
        assertTrue(facts.cte().contains("r.model = :model"));
        assertTrue(facts.cte().contains("m.model = :model"));
        assertEquals(user, facts.parameters().getValue("userId"));
        assertEquals("mock", facts.parameters().getValue("model"));
        assertFalse(facts.cte().contains("u.organization_id"));
    }
}
