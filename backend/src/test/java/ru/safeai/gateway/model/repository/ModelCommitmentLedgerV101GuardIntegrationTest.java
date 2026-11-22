package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
@Timeout(60)
class ModelCommitmentLedgerV101GuardIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    private ModelCommitmentLedgerFixture fixture;

    @BeforeEach
    void prepare() throws IOException, SQLException {
        fixture = new ModelCommitmentLedgerFixture(POSTGRES);
        fixture.reset();
        fixture.installV62();
    }

    @Test
    void missingLedgerIsNotSilentlyRecreated() {
        fixture.jdbc.execute("DROP VIEW public.model_cost_commitment_ledger_v62");
        assertGuardFailure("expected V62 ledger view is missing");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT to_regclass('public.model_cost_commitment_ledger_v62') IS NULL
                """, Boolean.class)).isTrue();
    }

    @Test
    void securityBarrierAndInvokerOptionsCannotBeReset() {
        fixture.jdbc.execute("""
                ALTER VIEW public.model_cost_commitment_ledger_v62
                SET (security_barrier = true, security_invoker = true)
                """);
        String original = fixture.viewDefinition();
        assertGuardFailure("unexpected ledger view options or identity");
        assertThat(fixture.viewDefinition()).isEqualTo(original);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT reloptions @> ARRAY['security_barrier=true', 'security_invoker=true']
                  FROM pg_catalog.pg_class
                 WHERE oid = 'public.model_cost_commitment_ledger_v62'::regclass
                """, Boolean.class)).isTrue();
    }

    @Test
    void missingUniqueConstraintPreventsReplacement() {
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts
                DROP CONSTRAINT uq_model_execution_attempt_number_v50
                """);
        assertGuardFailurePreservingLedger("expected valid V50 unique B-tree");
    }

    @Test
    void reversedUniqueKeyPreventsReplacement() {
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts
                DROP CONSTRAINT uq_model_execution_attempt_number_v50
                """);
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts
                ADD CONSTRAINT uq_model_execution_attempt_number_v50 UNIQUE (attempt_number, execution_plan_id)
                """);
        assertGuardFailurePreservingLedger("expected valid V50 unique B-tree");
    }

    @Test
    void deferrableUniqueKeyPreventsReplacement() {
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts
                DROP CONSTRAINT uq_model_execution_attempt_number_v50
                """);
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts
                ADD CONSTRAINT uq_model_execution_attempt_number_v50
                    UNIQUE (execution_plan_id, attempt_number) DEFERRABLE INITIALLY DEFERRED
                """);
        assertGuardFailurePreservingLedger("expected valid V50 unique B-tree");
    }

    @Test
    void nullableAttemptNumberPreventsReplacement() {
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts ALTER COLUMN attempt_number DROP NOT NULL
                """);
        assertGuardFailurePreservingLedger("expected valid V50 unique B-tree");
    }

    @Test
    void unexpectedViewColumnContractPreventsReplacement() {
        fixture.jdbc.execute("""
                ALTER VIEW public.model_cost_commitment_ledger_v62 RENAME COLUMN source TO altered_source
                """);
        assertGuardFailurePreservingLedger("unexpected ledger column contract");
    }

    @Test
    void repeatableReadCannotUseStaleCatalogSnapshotForReplacement() {
        String original = fixture.viewDefinition();
        assertThatThrownBy(() -> fixture.upgradeToV101(TransactionDefinition.ISOLATION_REPEATABLE_READ))
                .isInstanceOfSatisfying(DataAccessException.class, failure ->
                        assertThat(failure.getMostSpecificCause())
                                .isInstanceOfSatisfying(SQLException.class, sql -> {
                                    assertThat(sql.getSQLState()).isEqualTo("55000");
                                    assertThat(sql.getMessage()).contains("V101 requires READ COMMITTED");
                                }));
        assertThat(fixture.viewDefinition()).isEqualTo(original);
    }

    @Test
    void concurrentIndexMaintenancePreventsReplacementUntilReleased() throws SQLException, IOException {
        String original = fixture.viewDefinition();
        var before = fixture.globalResults();
        try (var conn = fixture.openConnection()) {
            conn.setAutoCommit(false);
            try (var statement = conn.createStatement()) {
                // Same relation lock mode used by concurrent index maintenance.
                statement.execute("LOCK TABLE public.model_execution_attempts IN SHARE UPDATE EXCLUSIVE MODE");
                assertThatThrownBy(fixture::upgradeToV101)
                        .isInstanceOfSatisfying(DataAccessException.class, failure ->
                                assertThat(failure.getMostSpecificCause())
                                        .isInstanceOfSatisfying(SQLException.class, sql ->
                                                assertThat(sql.getSQLState()).isEqualTo("55P03")));
                assertThat(fixture.viewDefinition()).isEqualTo(original);
                assertThat(fixture.globalResults()).containsExactlyElementsOf(before);
            } finally {
                conn.rollback();
            }
        }
        fixture.upgradeToV101();
        assertThat(fixture.viewDefinition()).containsIgnoringCase("LATERAL");
        assertThat(fixture.globalResults()).containsExactlyElementsOf(before);
    }

    @Test
    void sameNamedTableCannotSubstituteLedgerView() {
        fixture.jdbc.execute("DROP VIEW public.model_cost_commitment_ledger_v62");
        fixture.jdbc.execute("CREATE TABLE public.model_cost_commitment_ledger_v62 (id integer)");
        try {
            assertGuardFailure("expected V62 ledger view is missing");
            assertThat(fixture.jdbc.queryForObject("""
                    SELECT relkind::text FROM pg_catalog.pg_class
                    WHERE oid = 'public.model_cost_commitment_ledger_v62'::regclass
                    """, String.class)).isEqualTo("r");
        } finally {
            fixture.jdbc.execute("DROP TABLE public.model_cost_commitment_ledger_v62");
        }
    }

    @Test
    void nonUniqueIndexCannotSubstituteV50Constraint() {
        fixture.jdbc.execute("""
                ALTER TABLE public.model_execution_attempts
                DROP CONSTRAINT uq_model_execution_attempt_number_v50
                """);
        fixture.jdbc.execute("""
                CREATE INDEX uq_model_execution_attempt_number_v50
                    ON public.model_execution_attempts (execution_plan_id, attempt_number)
                """);
        assertGuardFailurePreservingLedger("expected valid V50 unique B-tree");
    }

    @Test
    void concurrentBudgetReservationsUseUpdatedLedgerAndSharedLock() throws Exception {
        fixture.upgradeToV101();
        UUID organization = UUID.randomUUID();
        var cap = new BigDecimal("0.10");
        var amount = new BigDecimal("0.075");
        var firstLocked = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fixture.reserveWithinBudget(
                    organization, amount, cap, () -> {}, () -> {
                        firstLocked.countDown();
                        await(release);
                    }));
            try {
                await(firstLocked);
                var second = executor.submit(() -> fixture.reserveWithinBudget(
                        organization, amount, cap, secondReady::countDown, () -> {}));
                await(secondReady);
                // The second transaction is at the production advisory lock,
                // while the first one still owns it. Never force or bypass it.
                assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(30, TimeUnit.SECONDS)).isFalse();
            } finally {
                release.countDown();
            }
        }
        var snapshot = new ModelRouteDecisionRepository(fixture.jdbc)
                .loadCommittedMonthlyCostSnapshot(organization,
                        ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO);
        assertThat(snapshot.actualCostUsd()).isZero();
        assertThat(snapshot.reservedCostUsd()).isEqualByComparingTo(amount);
        assertThat(snapshot.committedCostUsd()).isEqualByComparingTo(amount);
        assertThat(snapshot.unknownCommittedCostCount()).isZero();
    }

    @Test
    void concurrentReaderCausesBoundedRollbackThenRetrySucceeds() throws SQLException, IOException {
        String original = fixture.viewDefinition();
        var before = fixture.globalResults();
        try (var conn = fixture.openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var statement = conn.createStatement();
                     var result = statement.executeQuery("""
                             SELECT count(*) FROM public.model_cost_commitment_ledger_v62
                             """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isPositive();
                }
                // Separate physical connections; transaction retains ACCESS SHARE.
                assertThatThrownBy(fixture::upgradeToV101)
                        .isInstanceOfSatisfying(DataAccessException.class, failure ->
                                assertThat(failure.getMostSpecificCause())
                                        .isInstanceOfSatisfying(SQLException.class, sql ->
                                                assertThat(sql.getSQLState()).isEqualTo("55P03")));
                assertThat(fixture.viewDefinition()).isEqualTo(original);
                assertThat(fixture.globalResults()).containsExactlyElementsOf(before);
            } finally {
                conn.rollback();
            }
        }
        fixture.upgradeToV101();
        assertThat(fixture.viewDefinition()).containsIgnoringCase("LATERAL");
        assertThat(fixture.globalResults()).containsExactlyElementsOf(before);
    }

    private void assertGuardFailurePreservingLedger(String message) {
        String original = fixture.viewDefinition();
        assertGuardFailure(message);
        assertThat(fixture.viewDefinition()).isEqualTo(original);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent admission latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent admission interrupted", interrupted);
        }
    }

    private void assertGuardFailure(String message) {
        assertThatThrownBy(fixture::upgradeToV101)
                .isInstanceOfSatisfying(DataAccessException.class, failure ->
                        assertThat(failure.getMostSpecificCause())
                                .isInstanceOfSatisfying(SQLException.class, sql -> {
                                    assertThat(sql.getSQLState()).isEqualTo("55000");
                                    assertThat(sql.getMessage()).contains(message);
                                }));
    }
}
