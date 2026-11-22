package ru.safeai.gateway.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
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

import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Applies every Flyway migration separately against one real PostgreSQL database.
 *
 * <p>V0 is an intentional test-only role bootstrap. It must run before V1,
 * not be hidden or excluded to make the migration count look convenient.
 * Production migrations begin at V1 and currently extend through V102.</p>
 */
@Tag("integration")
@Testcontainers
class FlywaySequentialUpgradeIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void everyVersionCanBeAppliedInOrderWithoutDroppingSchema() throws SQLException {
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
        assertThat(versions).as("Expected test V0 and production versions through V102")
                .hasSizeGreaterThanOrEqualTo(103)
                .startsWith("0", "1")
                .contains("92", "93", "94", "95", "96", "97", "98", "99", "100", "101", "102");

        UUID existingOrganization = UUID.randomUUID();
        UUID existingActor = UUID.randomUUID();
        List<String> policiesBeforeHardening = List.of();
        UUID auditId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        List<String> evidenceBeforeV94 = List.of();
        List<String> ledgerIdentity = List.of();

        for (int index = 0; index < versions.size(); index++) {
            String version = versions.get(index);
            assertThat(version)
                    .as("Migration versions must be contiguous and ordered: V%s", index)
                    .isEqualTo(Integer.toString(index));

            Flyway step = flyway(dataSource, MigrationVersion.fromVersion(version));
            if (index == 94) {
                assertV94LockTimeoutRollsBackAllDdl(dataSource, jdbc, step);
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, false))
                        .containsExactlyElementsOf(evidenceBeforeV94);
            }
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
            if (index == 93) {
                seedV93Evidence(jdbc, existingOrganization, existingActor, auditId, outboxId);
                evidenceBeforeV94 = legacyEvidence(jdbc, existingOrganization, auditId, outboxId, false);
                assertThat(evidenceBeforeV94).hasSize(3);
            }
            if (index == 94) {
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .as("V94 must preserve audit, queued audit and preview proof byte values")
                        .containsExactlyElementsOf(evidenceBeforeV94);
                assertThat(policySnapshots(jdbc, existingOrganization))
                        .containsExactlyElementsOf(policiesBeforeHardening);
                assertV94Schema(jdbc, auditId, outboxId);
            }
            if (index == 95) {
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_catalog.pg_trigger
                        WHERE NOT tgisinternal AND tgenabled = 'O'
                          AND tgname IN ('trg_quota_reservation_v95', 'trg_z_physical_quota_attempt_v95')
                        """, Integer.class)).isEqualTo(2);
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
            }
            if (index == 96) {
                assertThat(jdbc.queryForObject("""
                        SELECT period_zone FROM public.chat_quota_calendar_v96 WHERE singleton
                        """, String.class)).isEqualTo("UTC");
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_catalog.pg_trigger
                        WHERE NOT tgisinternal AND tgenabled = 'O'
                          AND tgname IN ('trg_quota_reservation_v95', 'trg_z_physical_quota_attempt_v95',
                                         'trg_quota_org_write_v96', 'trg_quota_user_write_v96',
                                         'trg_quota_admission_snapshot_v96', 'trg_zz_physical_quota_v96')
                        """, Integer.class)).isEqualTo(6);
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
            }
            if (index == 97) {
                assertThat(jdbc.queryForObject("""
                        SELECT indisvalid AND indisready FROM pg_index
                        WHERE indexrelid = 'public.idx_model_execution_attempts_plan_started_v97'::regclass
                        """, Boolean.class)).isTrue();
            }
            if (index == 98) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM public.usage_export_jobs", Long.class)).isZero();
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
            }
            if (index == 99) {
                assertThat(jdbc.queryForObject("""
                        SELECT NOT prosecdef AND provolatile = 'v' AND proconfig IS NOT NULL
                        FROM pg_proc WHERE oid = 'public.guard_usage_export_job_v98()'::regprocedure
                        """, Boolean.class)).isTrue();
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
            }
            if (index == 100) {
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM public.model_physical_dispatch_receipts_v100",
                        Long.class)).isZero();
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
                ledgerIdentity = commitmentLedgerIdentity(jdbc);
            }
            if (index == 101) {
                assertThat(commitmentLedgerIdentity(jdbc)).containsExactlyElementsOf(ledgerIdentity);
                assertThat(jdbc.queryForObject("""
                        SELECT pg_get_viewdef('public.model_cost_commitment_ledger_v62'::regclass)
                        """, String.class)).containsIgnoringCase("LATERAL");
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
            }
            if (index == 102) {
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM public.platform_erasure_policy_v102
                        WHERE singleton AND chat_allowed AND knowledge_allowed AND version=0
                        """, Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM public.audit_event_types WHERE name='RETENTION_POLICY_CHANGED'
                        """, Long.class)).isEqualTo(1L);
                assertThat(legacyEvidence(jdbc, existingOrganization, auditId, outboxId, true))
                        .containsExactlyElementsOf(evidenceBeforeV94);
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

    private static List<String> commitmentLedgerIdentity(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT concat_ws('|', c.oid, c.relowner, coalesce(c.relacl::text, ''),
                                 coalesce(c.reloptions::text, ''), a.attnum, a.attname,
                                 format_type(a.atttypid, a.atttypmod))
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                WHERE c.oid = 'public.model_cost_commitment_ledger_v62'::regclass
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """, String.class);
    }

    private static void seedV93Evidence(JdbcTemplate jdbc, UUID org, UUID actor, UUID auditId, UUID outboxId) {
        assertThat(jdbc.update("""
                INSERT INTO public.audit_events (id, organization_id, event_type, details, created_at)
                VALUES (?, ?, 'USER_CREATED', '{"legacy":true}'::jsonb, clock_timestamp())
                """, auditId, org)).isEqualTo(1);
        assertThat(jdbc.update("""
                INSERT INTO public.audit_outbox (id, organization_id, event_type, details,
                    occurred_at, created_at, attempt_count, next_attempt_at)
                VALUES (?, ?, 'USER_CREATED', '{"legacy":true}'::jsonb,
                    clock_timestamp(), clock_timestamp(), 0, clock_timestamp())
                """, outboxId, org)).isEqualTo(1);
        assertThat(jdbc.update("""
                INSERT INTO public.model_policy_preview_confirmations
                    (organization_id, actor_id, token_hash, draft_hash, context_hash, lockout, expires_at, result_policy_id)
                SELECT ?, ?, repeat('a',64), repeat('b',64), repeat('c',64), false,
                    clock_timestamp() + interval '5 minutes', id
                FROM public.organization_model_policies WHERE organization_id=? AND version=2
                """, org, actor, org)).isEqualTo(1);
    }

    private static List<String> legacyEvidence(JdbcTemplate jdbc, UUID org, UUID auditId, UUID outboxId,
                                               boolean migrated) {
        // JSONB removes only the newly added column; all pre-existing values must match.
        String projection = migrated ? "(to_jsonb(t) - 'correlation_id')::text" : "to_jsonb(t)::text";
        return List.of(
                Objects.requireNonNull(jdbc.queryForObject("SELECT " + projection
                        + " FROM public.audit_events t WHERE id=?", String.class, auditId)),
                Objects.requireNonNull(jdbc.queryForObject("SELECT " + projection
                        + " FROM public.audit_outbox t WHERE id=?", String.class, outboxId)),
                Objects.requireNonNull(jdbc.queryForObject("""
                        SELECT to_jsonb(t)::text FROM public.model_policy_preview_confirmations t
                        WHERE organization_id=?
                        """, String.class, org)));
    }

    private static void assertV94LockTimeoutRollsBackAllDdl(DriverManagerDataSource dataSource,
                                                            JdbcTemplate jdbc, Flyway step) throws SQLException {
        // Block the SECOND ALTER TABLE, so the first one must be rolled back too.
        try (var blocker = dataSource.getConnection(); var statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE public.audit_outbox IN ACCESS SHARE MODE");
            try {
                FlywayException failure = assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> assertThrows(FlywayException.class, step::migrate));
                assertSqlState(failure, "55P03");
            } finally {
                blocker.rollback();
            }
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND column_name='correlation_id'
                  AND table_name IN ('audit_events','audit_outbox','model_route_decisions',
                                     'chat_turns','knowledge_ingestion_jobs')
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history WHERE version='94'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT to_regprocedure('public.guard_correlation_update_v94()') IS NULL
                """, Boolean.class)).isTrue();
    }

    private static void assertV94Schema(JdbcTemplate jdbc, UUID auditId, UUID outboxId) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND column_name='correlation_id'
                  AND udt_name='uuid' AND is_nullable='YES' AND column_default IS NULL
                  AND table_name IN ('audit_events','audit_outbox','model_route_decisions',
                                     'chat_turns','knowledge_ingestion_jobs')
                """, Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_catalog.pg_namespace ns ON ns.oid=idx.relnamespace
                WHERE ns.nspname='public' AND i.indisvalid AND i.indisready AND i.indpred IS NOT NULL
                  AND idx.relname IN ('idx_audit_events_correlation_v94','idx_audit_events_org_correlation_v94',
                                      'idx_model_route_correlation_v94','idx_chat_turn_correlation_v94')
                """, Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_trigger
                WHERE NOT tgisinternal AND tgenabled='O' AND
                    ((tgrelid='public.audit_outbox'::regclass AND tgname='trg_audit_outbox_correlation_v94') OR
                     (tgrelid='public.chat_turns'::regclass AND tgname='trg_chat_turn_correlation_v94') OR
                     (tgrelid='public.knowledge_ingestion_jobs'::regclass AND tgname='trg_ingestion_correlation_v94') OR
                     (tgrelid='public.audit_events'::regclass AND tgname='trg_audit_event_no_update_v68') OR
                     (tgrelid='public.model_route_decisions'::regclass AND tgname='trg_model_route_decisions_immutable_v45'))
                """, Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM public.audit_events WHERE id=?",
                UUID.class, auditId)).isNull();
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM public.audit_outbox WHERE id=?",
                UUID.class, outboxId)).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE public.audit_outbox SET correlation_id=? WHERE id=?",
                UUID.randomUUID(), outboxId)).satisfies(failure -> assertSqlState(failure, "23514"));
        // Legacy outbox delivery bookkeeping must remain writable.
        assertThat(jdbc.update("UPDATE public.audit_outbox SET next_attempt_at=next_attempt_at WHERE id=?",
                outboxId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT NOT prosecdef AND NOT EXISTS (
                    SELECT 1 FROM aclexplode(coalesce(proacl, acldefault('f', proowner)))
                    WHERE grantee=0 AND privilege_type='EXECUTE')
                FROM pg_proc WHERE oid='public.guard_correlation_update_v94()'::regprocedure
                """, Boolean.class)).isTrue();
    }

    private static void assertSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && expected.equals(sql.getSQLState())) {
                return;
            }
        }
        throw new AssertionError("Expected PostgreSQL SQLSTATE " + expected, failure);
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
