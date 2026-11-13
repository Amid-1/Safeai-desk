package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.config.UsageProperties;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class UsagePhysicalSpendRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(86_400);
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private static SimpleJdbcInsert insertMessage;
    private static SimpleJdbcInsert insertTurn;
    private static SimpleJdbcInsert insertPlan;
    private static SimpleJdbcInsert insertAttempt;
    private static UsagePhysicalSpendRepository repository;

    @BeforeAll
    static void createReportingSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var properties = new UsageProperties(null, null, null, null, null, null, null, null, null);
        repository = new UsagePhysicalSpendRepository(new UsageJdbcClients(dataSource, properties));

        // Isolated read-model fixture. Flyway and the ledger write path have their own integration suites.
        jdbc.execute("""
                create table public.chat_messages (
                    id uuid primary key, role text not null, status text not null,
                    pricing_status text, cost_usd numeric(30,12)
                );
                create table public.chat_turns (
                    id uuid primary key, assistant_message_id uuid references public.chat_messages(id)
                );
                create table public.model_execution_plans (
                    id uuid primary key, organization_id uuid not null,
                    chat_turn_id uuid not null references public.chat_turns(id)
                );
                create table public.model_execution_attempts (
                    id uuid primary key,
                    execution_plan_id uuid not null references public.model_execution_plans(id),
                    started_at timestamptz not null, outcome text not null,
                    outcome_certainty text not null, pricing_status text, usage_status text,
                    input_tokens bigint, output_tokens bigint, cost_usd numeric(30,12)
                );
                """);

        // Use the fixture's columns explicitly, independently of the Flyway schema indexed by the IDE.
        insertMessage = fixtureInsert(jdbc, "chat_messages",
                "id", "role", "status", "pricing_status", "cost_usd");
        insertTurn = fixtureInsert(jdbc, "chat_turns", "id", "assistant_message_id");
        insertPlan = fixtureInsert(jdbc, "model_execution_plans", "id", "organization_id", "chat_turn_id");
        insertAttempt = fixtureInsert(jdbc, "model_execution_attempts",
                "id", "execution_plan_id", "started_at", "outcome", "outcome_certainty",
                "pricing_status", "usage_status", "input_tokens", "output_tokens", "cost_usd");
    }

    private static SimpleJdbcInsert fixtureInsert(JdbcTemplate jdbc, String table, String... columns) {
        return new SimpleJdbcInsert(jdbc)
                .withSchemaName("public")
                .withTableName(table)
                .usingColumns(columns)
                .withoutTableColumnMetaDataAccess();
    }

    @Test
    void intervalCountsAttemptsWhileReconciliationUsesTheWholeSelectedPlan() {
        UUID matched = plan(TENANT_A, "6.00");
        attempt(matched, FROM.minusSeconds(1), "1.00");
        attempt(matched, FROM, "2.00");
        attempt(matched, TO.minusSeconds(1), "0.50");
        attempt(matched, TO, "2.50");

        UUID unknown = plan(TENANT_A, "4.00");
        attempt(unknown, FROM.plusSeconds(1), "4.00");
        attempt(unknown, TO.plusSeconds(1), null);

        UUID outside = plan(TENANT_A, "99.00");
        attempt(outside, TO, "99.00");

        UUID otherTenant = plan(TENANT_B, "20.00");
        attempt(otherTenant, FROM, "12.00");
        attempt(otherTenant, TO.minusSeconds(1), null);
        UUID mismatch = plan(TENANT_B, "10.00");
        attempt(mismatch, FROM, "3.00");

        var coverage = repository.findCoverage(FROM, TO, TENANT_A);
        assertThat(coverage.physicalAttempts()).isEqualTo(3);
        assertThat(coverage.knownCostUsd()).isEqualByComparingTo("6.50");
        assertThat(coverage.knownInputTokens()).isEqualTo(30);
        assertThat(coverage.knownOutputTokens()).isEqualTo(60);
        assertThat(coverage.costUnverifiableAttempts()).isZero();
        assertThat(coverage.costComplete()).isTrue();

        var reconciliation = repository.reconcile(FROM, TO, TENANT_A);
        assertThat(reconciliation.plans()).isEqualTo(2);
        assertThat(reconciliation.costMatchedPlans()).isEqualTo(1);
        assertThat(reconciliation.costMismatchPlans()).isZero();
        assertThat(reconciliation.unverifiablePlans()).isEqualTo(1);
        assertThat(reconciliation.fullyReconciled()).isFalse();

        var secondTenant = repository.findCoverage(FROM, TO, TENANT_B);
        assertThat(secondTenant.physicalAttempts()).isEqualTo(3);
        assertThat(secondTenant.knownCostUsd()).isEqualByComparingTo("15.00");
        assertThat(secondTenant.costUnverifiableAttempts()).isEqualTo(1);
        assertThat(secondTenant.usageUnverifiableAttempts()).isEqualTo(1);
        assertThat(secondTenant.costComplete()).isFalse();

        var global = repository.reconcile(FROM, TO, null);
        assertThat(global.plans()).isEqualTo(4);
        assertThat(global.costMatchedPlans()).isEqualTo(1);
        assertThat(global.costMismatchPlans()).isEqualTo(1);
        assertThat(global.unverifiablePlans()).isEqualTo(2);
    }

    private static UUID plan(UUID tenant, String messageCost) {
        UUID message = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        assertThat(insertMessage.execute(Map.of(
                "id", message,
                "role", "ASSISTANT",
                "status", "COMPLETED",
                "pricing_status", "PRICED",
                "cost_usd", new BigDecimal(messageCost)
        ))).isEqualTo(1);
        assertThat(insertTurn.execute(Map.of("id", turn, "assistant_message_id", message))).isEqualTo(1);
        assertThat(insertPlan.execute(Map.of(
                "id", plan, "organization_id", tenant, "chat_turn_id", turn
        ))).isEqualTo(1);
        return plan;
    }

    private static void attempt(UUID plan, Instant at, String cost) {
        boolean priced = cost != null;
        var values = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("execution_plan_id", plan)
                .addValue("started_at", Timestamp.from(at))
                .addValue("outcome", "SUCCEEDED")
                .addValue("outcome_certainty", "KNOWN_EXECUTED")
                .addValue("pricing_status", priced ? "PRICED" : "UNPRICED")
                .addValue("usage_status", priced ? "AVAILABLE" : "MISSING")
                .addValue("input_tokens", priced ? 10L : null)
                .addValue("output_tokens", priced ? 20L : null)
                .addValue("cost_usd", priced ? new BigDecimal(cost) : null);
        assertThat(insertAttempt.execute(values)).isEqualTo(1);
    }
}
