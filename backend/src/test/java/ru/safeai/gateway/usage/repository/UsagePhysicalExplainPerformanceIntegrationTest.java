// backend/src/test/java/ru/safeai/gateway/usage/repository/UsagePhysicalExplainPerformanceIntegrationTest.java
package ru.safeai.gateway.usage.repository;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.usage.config.UsageJdbcClients;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("performance")
@Testcontainers
@EnabledIfSystemProperty(named = "safeai.usage.performance", matches = "true")
class UsagePhysicalExplainPerformanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void explainActualRepositoryQueriesWithSelectiveTenantAndGlobalRange() throws Exception {
        var ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(ds);
        createFixture(jdbc);
        // Use the actual V97 script, in autocommit as required by CREATE INDEX CONCURRENTLY.
        try (var connection = ds.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V97__usage_physical_tenant_index.sql"));
        }
        assertThat(jdbc.queryForObject("""
                select i.indisvalid and i.indisready from pg_catalog.pg_index i
                where i.indexrelid = 'public.idx_model_execution_attempts_plan_started_v97'::regclass
                """, Boolean.class)).isTrue();
        jdbc.execute("analyze");

        List<String> plans = new ArrayList<>();
        var named = new ExplainingJdbc(ds, plans);
        var clients = mock(UsageJdbcClients.class);
        when(clients.query()).thenReturn(named);
        var repository = new UsagePhysicalSpendRepository(clients);
        verifyResults(jdbc, repository);
        verifyPlans(plans);
    }

    // Isolated read-model fixture with mandatory columns from the Flyway schema.
    // Ledger write invariants are exercised by the separate Flyway integration suites.
    private static void createFixture(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table public.chat_messages (
                    id uuid primary key, session_id uuid not null, organization_id uuid not null,
                    role varchar(50) not null, content text not null, status varchar(50) not null,
                    usage_status varchar(32) not null, pricing_status varchar(32) not null,
                    input_tokens integer, output_tokens integer, cost_usd numeric(30,12),
                    created_at timestamptz not null
                );
                create table public.chat_turns (
                    id uuid primary key, session_id uuid not null, organization_id uuid not null,
                    user_id uuid not null, client_request_id uuid not null,
                    request_content_hash varchar(64) not null,
                    provider_operation_id uuid not null unique,
                    user_message_id uuid not null references public.chat_messages(id),
                    assistant_message_id uuid references public.chat_messages(id),
                    state varchar(32) not null, created_at timestamptz not null, updated_at timestamptz not null,
                    unique (session_id, client_request_id)
                );
                create table public.model_execution_plans (
                    id uuid primary key, organization_id uuid not null,
                    chat_turn_id uuid not null unique references public.chat_turns(id),
                    provider_operation_id uuid not null unique, requested_model varchar(100) not null,
                    created_at timestamptz not null
                );
                create table public.model_execution_attempts (
                    id uuid primary key, execution_plan_id uuid not null references public.model_execution_plans(id),
                    attempt_number integer not null check (attempt_number >= 1),
                    provider_attempt_id uuid not null unique, provider_type varchar(32) not null,
                    provider_configuration_ref varchar(255) not null,
                    provider_configuration_version varchar(64) not null,
                    deployment_ref varchar(255) not null, deployment_version varchar(64) not null,
                    requested_physical_model varchar(100) not null,
                    started_at timestamptz not null, outcome varchar(24) not null,
                    outcome_certainty varchar(32) not null, retry_safety varchar(40) not null,
                    fallback_safety varchar(32) not null, pricing_status varchar(32),
                    usage_status varchar(32), input_tokens integer, output_tokens integer,
                    cost_usd numeric(30,12), created_at timestamptz not null,
                    unique (execution_plan_id, attempt_number)
                );
                insert into public.chat_messages
                    (id, session_id, organization_id, role, content, status, usage_status,
                     pricing_status, input_tokens, output_tokens, cost_usd, created_at)
                    select md5('m'||g)::uuid, md5('s'||g)::uuid, md5('o'||(g%1000))::uuid,
                        'ASSISTANT', 'fixture answer', 'COMPLETED', 'AVAILABLE', 'PRICED',
                        20, 40, 0.02,
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day' + interval '2 seconds'
                    from generate_series(1,100000) g;
                insert into public.chat_messages
                    (id, session_id, organization_id, role, content, status, usage_status,
                     pricing_status, input_tokens, output_tokens, cost_usd, created_at)
                    select md5('um'||g)::uuid, md5('s'||g)::uuid, md5('o'||(g%1000))::uuid,
                        'USER', 'fixture request', 'COMPLETED', 'NOT_APPLICABLE', 'NOT_APPLICABLE',
                        null, null, null,
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day'
                    from generate_series(1,100000) g;
                insert into public.chat_turns
                    (id, session_id, organization_id, user_id, client_request_id, request_content_hash,
                     provider_operation_id, user_message_id, assistant_message_id, state, created_at, updated_at)
                    select md5('t'||g)::uuid, md5('s'||g)::uuid, md5('o'||(g%1000))::uuid,
                        md5('u'||(g%1000))::uuid, md5('cr'||g)::uuid,
                        md5('request'||g) || md5('fixture'||g),
                        md5('op'||g)::uuid, md5('um'||g)::uuid, md5('m'||g)::uuid, 'SUCCEEDED',
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day',
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day' + interval '2 seconds'
                    from generate_series(1,100000) g;
                insert into public.model_execution_plans
                    (id, organization_id, chat_turn_id, provider_operation_id, requested_model, created_at)
                    select md5('p'||g)::uuid, md5('o'||(g%1000))::uuid,
                        md5('t'||g)::uuid, md5('op'||g)::uuid, 'logical-model',
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day'
                    from generate_series(1,100000) g;
                insert into public.model_execution_attempts
                    (id, execution_plan_id, attempt_number, provider_attempt_id, provider_type,
                     provider_configuration_ref, provider_configuration_version, deployment_ref, deployment_version,
                     requested_physical_model, started_at, outcome, outcome_certainty, retry_safety, fallback_safety,
                     pricing_status, usage_status, input_tokens, output_tokens, cost_usd, created_at)
                    select md5('a'||g||':'||n)::uuid, md5('p'||g)::uuid, n,
                        md5('pa'||g||':'||n)::uuid, 'mock', 'static:mock', 'static-v1',
                        'static:mock:fixture-model', 'static-v1', 'fixture-model',
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day' + n*interval '1 second',
                        'SUCCEEDED', 'KNOWN_EXECUTED', 'SAME_TARGET_RETRY_FORBIDDEN', 'FALLBACK_FORBIDDEN',
                        'PRICED','AVAILABLE',10,20,0.01,
                        '2026-06-01T00:00:00Z'::timestamptz + (g%30)*interval '1 day'
                    from generate_series(1,100000) g cross join generate_series(1,2) n;
                create index idx_model_execution_attempts_started_plan_v74
                    on public.model_execution_attempts(started_at, execution_plan_id);
                create index idx_model_execution_plans_organization_id_v74
                    on public.model_execution_plans(organization_id, id);
                """);
    }

    private static void verifyResults(JdbcTemplate jdbc, UsagePhysicalSpendRepository repository) {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = from.plusSeconds(30 * 86400L);
        UUID tenant = Objects.requireNonNull(jdbc.queryForObject("select md5('o1')::uuid", UUID.class),
                "Fixture tenant is missing");
        UUID actor = Objects.requireNonNull(jdbc.queryForObject("select md5('u1')::uuid", UUID.class),
                "Fixture actor is missing");
        var scoped = repository.findCoverage(from, to, tenant);
        assertThat(scoped.physicalAttempts()).isEqualTo(200);
        assertThat(scoped.knownInputTokens()).isEqualTo(2000);
        assertThat(scoped.knownOutputTokens()).isEqualTo(4000);
        assertThat(scoped.knownCostUsd()).isEqualByComparingTo("2.00");
        var scopedReconciliation = repository.reconcile(from, to, tenant);
        assertThat(scopedReconciliation.costMatchedPlans()).isEqualTo(100);
        assertThat(scopedReconciliation.costMismatchPlans()).isZero();
        assertThat(scopedReconciliation.unverifiablePlans()).isZero();
        var scopedProviders = repository.findProviders(from, to, tenant, "logical-model", actor);
        assertThat(scopedProviders).hasSize(1);
        assertThat(scopedProviders.getFirst().attempts()).isEqualTo("200");
        assertThat(new BigDecimal(scopedProviders.getFirst().knownCostUsd())).isEqualByComparingTo("2.00");
        var global = repository.findCoverage(from, to, null);
        assertThat(global.physicalAttempts()).isEqualTo(200000);
        assertThat(global.knownCostUsd()).isEqualByComparingTo("2000.00");
        var globalReconciliation = repository.reconcile(from, to, null);
        assertThat(globalReconciliation.costMatchedPlans()).isEqualTo(100000);
        assertThat(globalReconciliation.costMismatchPlans()).isZero();
        assertThat(globalReconciliation.unverifiablePlans()).isZero();
        var globalProviders = repository.findProviders(from, to, null, null, null);
        assertThat(globalProviders).hasSize(1);
        assertThat(globalProviders.getFirst().attempts()).isEqualTo("200000");
        assertThat(repository.findCoverage(from, from.plusSeconds(86400), null).physicalAttempts())
                .isEqualTo(6666);
        assertThat(repository.findProviders(from, to, tenant, "other-model", actor)).isEmpty();
        assertThat(repository.findProviders(from, to, tenant, null, UUID.randomUUID())).isEmpty();
    }

    private static void verifyPlans(List<String> plans) throws Exception {
        assertThat(plans).hasSize(9);
        Path dir = Path.of("target", "usage-explain");
        Files.createDirectories(dir);
        long sla = Long.parseLong(System.getProperty("safeai.usage.physical-explain-sla-ms", "5000"));
        assertThat(sla).isPositive();
        var pattern = Pattern.compile("Execution Time: ([0-9.]+) ms");
        for (int i = 0; i < plans.size(); i++) {
            String plan = plans.get(i);
            Files.writeString(dir.resolve("physical-" + i + ".txt"), plan);
            assertThat(plan).contains("Buffers:", "Planning Time:", "Execution Time:");
            var match = pattern.matcher(plan);
            assertThat(match.find()).isTrue();
            assertThat(Double.parseDouble(match.group(1))).isLessThanOrEqualTo(sla);
        }
    }

    // Typed JDBC interception: no raw RowMapper and no duplicated SQL implementation.
    @NullMarked
    private static final class ExplainingJdbc extends NamedParameterJdbcTemplate {
        private final NamedParameterJdbcTemplate delegate;
        private final List<String> plans;

        ExplainingJdbc(DriverManagerDataSource dataSource, List<String> plans) {
            super(dataSource);
            this.delegate = new NamedParameterJdbcTemplate(dataSource);
            this.plans = plans;
        }

        @Override
        @SuppressWarnings("SqlSourceToSinkFlow") // Only repository SQL; bind parameters stay separate.
        public <T extends @Nullable Object> T queryForObject(
                String sql, SqlParameterSource params, RowMapper<T> mapper) {
            explain(sql, params);
            return delegate.queryForObject(sql, params, mapper);
        }

        @Override
        @SuppressWarnings("SqlSourceToSinkFlow") // Only repository SQL; bind parameters stay separate.
        public <T extends @Nullable Object> List<T> query(
                String sql, SqlParameterSource params, RowMapper<T> mapper) {
            explain(sql, params);
            return delegate.query(sql, params, mapper);
        }

        // The only added SQL is a constant EXPLAIN prefix. Never interpolate parameter values.
        private void explain(String sql, SqlParameterSource params) {
            plans.add(String.join("\n", delegate.queryForList(
                    "explain (analyze, buffers, format text) " + sql, params, String.class)));
        }
    }
}
