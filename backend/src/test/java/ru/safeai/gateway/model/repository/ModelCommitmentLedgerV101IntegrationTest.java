package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@Timeout(120)
class ModelCommitmentLedgerV101IntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void everyLedgerRowAndMonthlyCommitmentRemainIdenticalAcrossUpgrade() throws IOException, SQLException {
        var fixture = new ModelCommitmentLedgerFixture(POSTGRES);
        fixture.installV62();
        fixture.addBackground(1000, false);
        var repository = new ModelRouteDecisionRepository(fixture.jdbc);
        var before = repository.loadCommittedMonthlyCostSnapshot(
                fixture.tenant(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO);
        assertFinancialCases(before);
        createBaseline(fixture.jdbc);
        assertLedgerColumnContract(fixture.jdbc);
        fixture.jdbc.update("""
                INSERT INTO public.baseline_v62
                    (organization_id, model_route_decision_id, execution_attempt_id,
                     ledger_at, source, actual_cost_usd, reserved_cost_usd,
                     unknown_cost_count, carry_forward)
                SELECT organization_id, model_route_decision_id, execution_attempt_id,
                       ledger_at, source, actual_cost_usd, reserved_cost_usd,
                       unknown_cost_count, carry_forward
                FROM public.model_cost_commitment_ledger_v62
                """);
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM public.baseline_v62", Long.class))
                .isGreaterThan(2000L);
        fixture.upgradeToV101();
        assertLedgerColumnContract(fixture.jdbc);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT count(*) FROM (
                    (SELECT organization_id, model_route_decision_id, execution_attempt_id,
                            ledger_at, source, actual_cost_usd, reserved_cost_usd,
                            unknown_cost_count, carry_forward
                     FROM public.baseline_v62
                     EXCEPT ALL
                     SELECT organization_id, model_route_decision_id, execution_attempt_id,
                            ledger_at, source, actual_cost_usd, reserved_cost_usd,
                            unknown_cost_count, carry_forward
                     FROM public.model_cost_commitment_ledger_v62)
                    UNION ALL
                    (SELECT organization_id, model_route_decision_id, execution_attempt_id,
                            ledger_at, source, actual_cost_usd, reserved_cost_usd,
                            unknown_cost_count, carry_forward
                     FROM public.model_cost_commitment_ledger_v62
                     EXCEPT ALL
                     SELECT organization_id, model_route_decision_id, execution_attempt_id,
                            ledger_at, source, actual_cost_usd, reserved_cost_usd,
                            unknown_cost_count, carry_forward
                     FROM public.baseline_v62)
                ) differences
                """, Long.class)).isZero();
        var after = repository.loadCommittedMonthlyCostSnapshot(
                fixture.tenant(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO);
        assertFinancialCases(after);
        assertThat(after).isEqualTo(before);
        var absent = repository.loadCommittedMonthlyCostSnapshot(
                UUID.randomUUID(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO);
        assertThat(absent.committedCostUsd()).isZero();
        assertThat(absent.unknownCommittedCostCount()).isZero();
    }

    private static void createBaseline(JdbcTemplate jdbc) {
        // Explicit schema makes all operands resolvable without inferring a
        // SELECT * projection from a dynamically created CTAS relation.
        jdbc.execute("""
                CREATE TABLE public.baseline_v62 (
                    organization_id uuid,
                    model_route_decision_id uuid,
                    execution_attempt_id uuid,
                    ledger_at timestamptz,
                    source text,
                    actual_cost_usd numeric,
                    reserved_cost_usd numeric,
                    unknown_cost_count bigint,
                    carry_forward boolean
                )
                """);
    }

    private static void assertLedgerColumnContract(JdbcTemplate jdbc) {
        // Explicit projections must not accidentally hide a future new
        // column, a reordered column or a changed PostgreSQL data type.
        String columnSql = """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """;
        var expected = jdbc.queryForList(columnSql, "baseline_v62");
        assertThat(expected).hasSize(9);
        assertThat(jdbc.queryForList(columnSql, "model_cost_commitment_ledger_v62"))
                .containsExactlyElementsOf(expected);
    }

    static void assertFinancialCases(ModelRouteDecisionRepository.MonthlyCostSnapshot snapshot) {
        assertThat(snapshot.actualCostUsd()).isEqualByComparingTo("0.10");
        assertThat(snapshot.reservedCostUsd()).isEqualByComparingTo("2.50");
        assertThat(snapshot.committedCostUsd()).isEqualByComparingTo("2.60");
        assertThat(snapshot.unknownCommittedCostCount()).isEqualTo(4);
    }
}
