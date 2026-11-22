package ru.safeai.gateway.model.repository;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ModelCommitmentLedgerFixture {
    static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final String VIEW_START = "CREATE VIEW public.model_cost_commitment_ledger_v62 AS";
    private static final String VIEW_END = "SELECT * FROM legacy;";
    private static final String GLOBAL_LEDGER_SQL = """
            SELECT organization_id, sum(actual_cost_usd) AS actual,
                   sum(reserved_cost_usd) AS reserved, sum(unknown_cost_count) AS unknown
            FROM public.model_cost_commitment_ledger_v62
            WHERE carry_forward OR (ledger_at >= ? AND ledger_at < ?)
            GROUP BY organization_id
            ORDER BY organization_id
            """;

    private final DriverManagerDataSource source;
    final JdbcTemplate jdbc;

    ModelCommitmentLedgerFixture(PostgreSQLContainer postgres) {
        source = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
    }

    void installV62() throws IOException, SQLException {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("model/commitment-ledger-v101-fixture.sql"));
        }
        String migration = new ClassPathResource("db/migration/V62__model_control_plane_hardening.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int start = migration.indexOf(VIEW_START);
        int end = migration.indexOf(VIEW_END, start);
        if (start < 0 || end < start) {
            throw new IllegalStateException("Cannot locate the historical V62 ledger definition");
        }
        jdbc.execute(migration.substring(start, end + VIEW_END.length()));
    }

    // This helper is used exclusively with a disposable Testcontainers database.
    void reset() {
        jdbc.execute("""
                DROP VIEW IF EXISTS public.model_cost_commitment_ledger_v62;
                DROP TABLE IF EXISTS public.model_execution_attempts, public.model_execution_plans,
                    public.chat_turns, public.chat_messages, public.model_route_decisions,
                    public.organization_model_policies
                """);
    }

    Connection openConnection() throws SQLException {
        return source.getConnection();
    }

    String viewDefinition() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT pg_get_viewdef('public.model_cost_commitment_ledger_v62'::regclass)
                """, String.class));
    }

    void upgradeToV101() throws IOException {
        upgradeToV101(TransactionDefinition.ISOLATION_DEFAULT);
    }

    void upgradeToV101(int isolation) throws IOException {
        String migration = new ClassPathResource("db/migration/V101__model_commitment_ledger_lookup.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(isolation);
        transaction.executeWithoutResult(status -> jdbc.execute(migration));
    }

    UUID tenant() {
        return Objects.requireNonNull(jdbc.queryForObject("select md5('org0')::uuid", UUID.class));
    }

    private static Object[] parameters(UUID organization) {
        return new Object[]{organization, Timestamp.from(FROM), Timestamp.from(TO)};
    }

    UUID hotTenant() {
        return Objects.requireNonNull(jdbc.queryForObject("select md5('hot')::uuid", UUID.class));
    }

    void addBackground(int count, boolean dense) {
        jdbc.update("""
                INSERT INTO public.model_route_decisions
                    (id, organization_id, created_at, outcome, estimated_max_cost_usd,
                     pricing_complete, budget_enforcement, user_id, chat_id, client_request_id,
                     request_content_hash, reason, decision_sha256, chat_turn_id)
                SELECT md5('d'||g)::uuid,
                       md5(CASE WHEN ? AND (g / 1000) % 2 = 0 THEN 'hot'
                                ELSE 'org'||(g % 1000 + 1) END)::uuid,
                       '2026-09-15T00:00:00Z', 'ALLOWED', 1, true, 'SOFT',
                       md5('user'||g)::uuid, md5('session'||g)::uuid, md5('request'||g)::uuid,
                       encode(sha256(convert_to('request:'||g, 'UTF8')), 'hex'),
                       'RUNTIME_ONLY_MATCH',
                       encode(sha256(convert_to('fixture-decision:'||g, 'UTF8')), 'hex'),
                       md5('t'||g)::uuid
                FROM generate_series(1000, ?) g
                """, dense, count + 999);
        jdbc.update("""
                INSERT INTO public.chat_turns
                    (id, model_route_decision_id, state, organization_id, user_id, session_id,
                     client_request_id, request_content_hash, provider_operation_id,
                     user_message_id, created_at, updated_at)
                SELECT d.chat_turn_id, d.id,
                       CASE WHEN CASE WHEN ? THEN g % 3 = 0 ELSE g % 100 = 0 END
                            THEN 'PROCESSING' ELSE 'SUCCEEDED' END,
                       d.organization_id, d.user_id, d.chat_id, d.client_request_id,
                       d.request_content_hash, md5('operation'||g)::uuid,
                       md5('user-message'||g)::uuid, d.created_at, d.created_at
                FROM generate_series(1000, ?) g
                JOIN public.model_route_decisions d ON d.id = md5('d'||g)::uuid
                """, dense, count + 999);
        jdbc.update("""
                INSERT INTO public.model_execution_plans
                    (id, organization_id, model_route_decision_id, chat_turn_id,
                     provider_operation_id, requested_model, created_at)
                SELECT md5('p'||g)::uuid, t.organization_id,
                       t.model_route_decision_id, t.id, t.provider_operation_id,
                       'gpt-test', t.created_at
                FROM generate_series(1000, ?) g
                JOIN public.chat_turns t ON t.id = md5('t'||g)::uuid
                """, count + 999);
        jdbc.update("""
                INSERT INTO public.model_execution_attempts
                    (id, execution_plan_id, attempt_number, started_at, outcome, outcome_certainty,
                     retry_safety, usage_status, specialized_dimensions_valid, pricing_status, cost_usd,
                     provider_attempt_id, provider_type, provider_configuration_ref,
                     provider_configuration_version, deployment_ref, deployment_version,
                     requested_physical_model, fallback_safety, created_at)
                SELECT md5('a'||g||':'||n)::uuid, md5('p'||g)::uuid, n,
                       '2026-09-15T00:00:00Z',
                       CASE WHEN n = 1 THEN 'FAILED'
                            WHEN state.active THEN 'STARTED' ELSE 'SUCCEEDED' END,
                       CASE WHEN n = 1 THEN 'KNOWN_NOT_EXECUTED'
                            WHEN state.active THEN 'AMBIGUOUS' ELSE 'KNOWN_EXECUTED' END,
                       CASE WHEN n = 1 THEN 'SAME_TARGET_RETRY_ALLOWED' ELSE 'SAME_TARGET_RETRY_FORBIDDEN' END,
                       CASE WHEN n = 2 AND NOT state.active THEN 'AVAILABLE' END,
                       CASE WHEN n = 2 AND NOT state.active THEN true END,
                       CASE WHEN n = 2 AND NOT state.active THEN 'PRICED' END,
                       CASE WHEN n = 2 AND NOT state.active THEN 0.02 END,
                       md5('provider-attempt'||g||':'||n)::uuid,
                       'openai', 'static:openai', 'static-v1', 'static:openai:gpt-test',
                       'static-v1', 'gpt-test', 'FALLBACK_FORBIDDEN', '2026-09-15T00:00:00Z'
                FROM generate_series(1000, ?) g CROSS JOIN generate_series(1, 2) n
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN ? THEN g % 3 = 0 ELSE g % 100 = 0 END AS active
                ) state
                """, count + 999, dense);
        jdbc.execute("ANALYZE");
    }

    String explainTenant() {
        return explainTenant(tenant());
    }

    String explainTenant(UUID organization) {
        return String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + ModelRouteDecisionRepository.MONTHLY_COMMITMENT_SQL,
                String.class, parameters(organization)));
    }

    String explainTenantJson(UUID organization) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + ModelRouteDecisionRepository.MONTHLY_COMMITMENT_SQL,
                String.class, parameters(organization)));
    }

    String explainGlobalJson() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + GLOBAL_LEDGER_SQL,
                String.class, Timestamp.from(FROM), Timestamp.from(TO)));
    }

    String explainGlobal() {
        return String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + GLOBAL_LEDGER_SQL,
                String.class, Timestamp.from(FROM), Timestamp.from(TO)));
    }

    List<Map<String, Object>> globalResults() {
        return jdbc.queryForList(GLOBAL_LEDGER_SQL, Timestamp.from(FROM), Timestamp.from(TO));
    }

    // Isolated ledger fixture: exercise the production lock and read SQL,
    // then publish a minimal pending reservation in the same transaction.
    // The full-schema V100 admission tests independently exercise DB guards.
    boolean reserveWithinBudget(UUID organization, BigDecimal amount, BigDecimal cap,
                                Runnable beforeLock, Runnable afterLock) {
        var repository = new ModelRouteDecisionRepository(jdbc);
        return Boolean.TRUE.equals(new TransactionTemplate(new DataSourceTransactionManager(source))
                .execute(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '10s'");
                    jdbc.execute("SET LOCAL statement_timeout = '20s'");
                    beforeLock.run();
                    repository.lockOrganizationBudget(organization);
                    afterLock.run();
                    var snapshot = repository.loadCommittedMonthlyCostSnapshot(organization, FROM, TO);
                    if (snapshot.unknownCommittedCostCount() != 0
                            || snapshot.committedCostUsd().add(amount).compareTo(cap) > 0) {
                        return false;
                    }
                    UUID decision = UUID.randomUUID();
                    UUID request = UUID.randomUUID();
                    UUID turn = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO public.model_route_decisions
                                (id, organization_id, created_at, outcome, estimated_max_cost_usd,
                                 pricing_complete, budget_enforcement, monthly_budget_usd,
                                 user_id, chat_id, client_request_id, request_content_hash,
                                 reason, decision_sha256, chat_turn_id)
                            VALUES (?, ?, ?, 'ALLOWED', ?, true, 'HARD', ?, ?, ?, ?,
                                    encode(sha256(convert_to(?::text, 'UTF8')), 'hex'),
                                    'RUNTIME_ONLY_MATCH',
                                    encode(sha256(convert_to(?::text, 'UTF8')), 'hex'), ?)
                            """, decision, organization, Timestamp.from(FROM), amount, cap,
                            UUID.randomUUID(), UUID.randomUUID(), request, request, decision, turn);
                    jdbc.update("""
                            INSERT INTO public.chat_turns
                                (id, model_route_decision_id, state, organization_id, user_id, session_id,
                                 client_request_id, request_content_hash, provider_operation_id,
                                 user_message_id, created_at, updated_at)
                            SELECT d.chat_turn_id, d.id, 'PROCESSING', d.organization_id, d.user_id, d.chat_id,
                                   d.client_request_id, d.request_content_hash, ?::uuid,
                                   ?::uuid, d.created_at, d.created_at
                            FROM public.model_route_decisions d WHERE d.id = ?
                            """, UUID.randomUUID(), UUID.randomUUID(), decision);
                    return true;
                }));
    }
}
