package ru.safeai.gateway.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Applies every Flyway migration separately against one real PostgreSQL database.
 *
 * <p>V0 is an intentional test-only role bootstrap. It must run before V1,
 * not be hidden or excluded to make the migration count look convenient.
 * Production migrations begin at V1 and currently extend through V93.</p>
 */
@Tag("integration")
@Testcontainers
class FlywaySequentialUpgradeIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void everyVersionCanBeAppliedInOrderWithoutDroppingSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .as("Flyway must target its actual isolated Testcontainers database")
                .isEqualTo(POSTGRES.getDatabaseName());

        Long publicSchemaOid = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT oid::bigint FROM pg_catalog.pg_namespace
                WHERE nspname = 'public'
                """, Long.class), "public schema must exist before migration");

        Flyway catalog = flyway(dataSource, null);
        List<String> versions = Arrays.stream(catalog.info().all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .map(MigrationVersion::getVersion)
                .toList();

        // Validate the complete catalog, not just the migration that failed.
        assertThat(versions).as("Expected test V0 and production versions through V93")
                .hasSizeGreaterThanOrEqualTo(94)
                .startsWith("0", "1")
                .contains("92", "93");

        UUID existingOrganization = UUID.randomUUID();
        UUID existingActor = UUID.randomUUID();
        List<String> policiesBeforeHardening = List.of();

        for (int index = 0; index < versions.size(); index++) {
            String version = versions.get(index);
            assertThat(version)
                    .as("Migration versions must be contiguous and ordered: V%s", index)
                    .isEqualTo(Integer.toString(index));

            Flyway step = flyway(dataSource, MigrationVersion.fromVersion(version));
            var result = step.migrate();

            // V0 and V1 are TWO distinct migrations. Later targets add ONE each.
            assertThat(result.migrationsExecuted)
                    .as("Exactly one migration must be applied at target V%s", version)
                    .isEqualTo(1);

            List<String> recorded = jdbc.queryForList("""
                    SELECT version FROM public.flyway_schema_history
                    WHERE success = true AND version IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class);
            assertThat(recorded)
                    .as("Applied Flyway history must be an exact prefix through V%s", version)
                    .containsExactlyElementsOf(versions.subList(0, index + 1));

            assertThat(step.info().current())
                    .as("Flyway must report current version after V%s", version)
                    .isNotNull();
            assertThat(step.info().current().getVersion().getVersion())
                    .isEqualTo(version);

            assertThat(jdbc.queryForObject("""
                    SELECT oid::bigint FROM pg_catalog.pg_namespace
                    WHERE nspname = 'public'
                    """, Long.class))
                    .as("Migration V%s must not drop and recreate the public schema", version)
                    .isEqualTo(publicSchemaOid);

            if (index == 0) {
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_catalog.pg_roles
                        WHERE rolname = 'safeai_chat_erasure_worker'
                          AND rolcanlogin AND NOT rolsuper
                        """, Integer.class))
                        .as("Test V0 must bootstrap an isolated non-superuser login")
                        .isEqualTo(1);
            }

            if (index == 21) {
                // V21 uses CREATE INDEX CONCURRENTLY. Verify the index exists
                // AND is ready and valid, not merely recorded as migrated.
                assertThat(jdbc.queryForObject("""
                        SELECT i.indisvalid AND i.indisready
                        FROM pg_catalog.pg_index i
                        JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                        JOIN pg_catalog.pg_namespace ns ON ns.oid = idx.relnamespace
                        WHERE ns.nspname = 'public'
                          AND idx.relname = 'idx_refresh_tokens_cleanup_batch'
                        """, Boolean.class))
                        .as("V21 concurrent cleanup index must be ready and valid")
                        .isTrue();
            }

            if (index == 91) {
                // Seed legacy USD-only and RUB-backed evidence before V92.
                // V92 must preserve the existing immutable versions verbatim.
                assertV91RubColumnsExist(jdbc);
                transaction.executeWithoutResult(status ->
                        seedV91Policies(jdbc, existingOrganization, existingActor));
                assertFixtureActorRole(jdbc, existingActor);
                policiesBeforeHardening = policySnapshots(jdbc, existingOrganization);
                assertThat(policiesBeforeHardening).hasSize(2);
            }

            if (index == 92) {
                assertV91RubColumnsExist(jdbc);
                assertFixtureActorRole(jdbc, existingActor);
                assertRolePresenceStillEnforced(jdbc, existingOrganization, existingActor);
                assertThat(policySnapshots(jdbc, existingOrganization))
                        .as("V92 must not rewrite historical policy evidence")
                        .containsExactlyElementsOf(policiesBeforeHardening);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_catalog.pg_constraint
                        WHERE conrelid = 'public.organization_model_policies'::regclass
                          AND convalidated
                          AND conname IN (
                              'chk_model_policy_usd_finite_v92',
                              'chk_model_policy_rub_dates_finite_v92'
                          )
                        """, Integer.class))
                        .as("Both V92 constraints must be fully validated")
                        .isEqualTo(2);
            }
        }

        Flyway finalFlyway = flyway(dataSource, null);
        finalFlyway.validate();
        assertThat(finalFlyway.migrate().migrationsExecuted)
                .as("A second full migration must be an idempotent no-op")
                .isZero();

        List<String> finalHistory = jdbc.queryForList("""
                SELECT version FROM public.flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank
                """, String.class);
        assertThat(finalHistory)
                .as("Re-running Flyway must not change the migration ledger")
                .containsExactlyElementsOf(versions);
    }

    private static void seedV91Policies(JdbcTemplate jdbc, UUID organizationId, UUID actor) {
        String name = "V92 upgrade " + organizationId;
        assertThat(jdbc.update("""
                INSERT INTO public.organizations (
                    id, name, normalized_name, enabled, created_at, updated_at, version, auth_version
                ) VALUES (?, ?, public.normalize_organization_name(?), true,
                    '2026-11-16T07:00:00Z', '2026-11-16T07:00:00Z', 0, 0)
                """, organizationId, name, name)).isEqualTo(1);
        // V20 enforces role presence at transaction commit. User creation and
        // role assignment must share the same transaction, exactly as in production.
        assertThat(insertFixtureActor(jdbc, organizationId, actor)).isEqualTo(1);
        assertThat(jdbc.update("""
                INSERT INTO public.user_roles (user_id, role_id)
                SELECT ?, id FROM public.roles WHERE name = 'ADMIN'
                """, actor))
                .as("V2 must provide exactly one ADMIN role for the policy actor")
                .isEqualTo(1);
        assertThat(jdbc.update("""
                INSERT INTO public.organization_model_policies (
                    id, organization_id, version, budget_enforcement, created_by_user_id,
                    created_at, max_request_cost_usd, monthly_budget_usd
                ) VALUES (?, ?, 1, 'HARD', ?, '2026-11-16T07:00:00Z', 1, 100)
                """, UUID.randomUUID(), organizationId, actor)).isEqualTo(1);
        insertV91RubPolicy(jdbc, organizationId, actor);
    }

    private static int insertFixtureActor(JdbcTemplate jdbc, UUID organizationId, UUID actor) {
        return jdbc.update("""
                INSERT INTO public.users (
                    id, organization_id, email, password_hash, full_name, enabled,
                    created_at, updated_at, token_version, version
                ) VALUES (?, ?, ?, 'encoded', 'V92 upgrade actor', true,
                    '2026-11-16T07:00:00Z', '2026-11-16T07:00:00Z', 0, 0)
                """, actor, organizationId, "v92-" + actor + "@test.local");
    }

    // The test intentionally uses the schema at V91, not the IDE's attached
    // database schema. Actual column resolution is asserted against PostgreSQL.
    @SuppressWarnings("SqlResolve")
    private static void insertV91RubPolicy(JdbcTemplate jdbc, UUID organizationId, UUID actor) {
        assertThat(jdbc.update("""
                INSERT INTO public.organization_model_policies (
                    id, organization_id, version, budget_enforcement, created_by_user_id,
                    created_at, max_request_cost_usd, monthly_budget_usd,
                    max_request_cost_rub, monthly_budget_rub, rub_per_usd,
                    rub_quote_date, rub_effective_date, rub_fetched_at
                ) VALUES (?, ?, 2, 'HARD', ?, '2026-11-17T07:00:00Z',
                    0.055555555555, 111.111111111111, 5, 10000, 90,
                    '2026-11-17', '2026-11-16', '2026-11-17T06:59:59Z')
                """, UUID.randomUUID(), organizationId, actor)).isEqualTo(1);
    }

    private static void assertV91RubColumnsExist(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT column_name::text
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'organization_model_policies'
                  AND column_name IN (
                      'max_request_cost_rub', 'monthly_budget_rub', 'rub_per_usd',
                      'rub_quote_date', 'rub_effective_date', 'rub_fetched_at'
                  )
                """, String.class))
                .as("All six V91 RUB evidence columns must exist in the actual database")
                .containsExactlyInAnyOrder("max_request_cost_rub", "monthly_budget_rub",
                        "rub_per_usd", "rub_quote_date", "rub_effective_date", "rub_fetched_at");
    }

    private static void assertFixtureActorRole(JdbcTemplate jdbc, UUID actor) {
        assertThat(jdbc.queryForList("""
                SELECT r.name FROM public.user_roles ur
                    JOIN public.roles r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                """, String.class, actor))
                .as("Committed policy actor must retain the ADMIN role")
                .containsExactly("ADMIN");
    }

    private static void assertRolePresenceStillEnforced(
            JdbcTemplate jdbc, UUID organizationId, UUID actor
    ) {
        UUID invalidActor = UUID.randomUUID();
        // Outside TransactionTemplate each statement commits independently.
        // The deferred constraint must reject a roleless user at that boundary.
        assertThatThrownBy(() -> insertFixtureActor(jdbc, organizationId, invalidActor))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("User " + invalidActor + " must have at least one role");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users WHERE id = ?",
                Integer.class, invalidActor)).isZero();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM public.user_roles WHERE user_id = ?", actor))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("User " + actor + " must have at least one role");
        assertFixtureActorRole(jdbc, actor);
    }

    private static List<String> policySnapshots(JdbcTemplate jdbc, UUID organizationId) {
        return jdbc.queryForList("""
                SELECT to_jsonb(p)::text
                FROM public.organization_model_policies p
                WHERE organization_id = ?
                ORDER BY version
                """, String.class, organizationId);
    }

    private static Flyway flyway(
            DriverManagerDataSource dataSource,
            MigrationVersion target
    ) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .validateOnMigrate(true);
        // V21 executes CREATE/DROP INDEX CONCURRENTLY outside transactions.
        // PostgreSQL transactional advisory locks can deadlock with this DDL.
        // Session-level advisory locks still serialize Flyway migrations.
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway configured = configuration.load();
        assertThat(configured.getConfigurationExtension(
                PostgreSQLConfigurationExtension.class).isTransactionalLock())
                .as("Flyway must use session advisory locks for concurrent indexes")
                .isFalse();
        return configured;
    }
}
