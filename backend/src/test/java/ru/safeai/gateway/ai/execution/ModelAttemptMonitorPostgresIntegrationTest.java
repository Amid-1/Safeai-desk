package ru.safeai.gateway.ai.execution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@Testcontainers
class ModelAttemptMonitorPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void boundedSqlDistinguishesCostsAndReportsFailureAndRecovery() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(ds);
        createFixture(jdbc);
        jdbc.execute("""
                WITH fixture(started_at, outcome, outcome_certainty, usage_status,
                             specialized_dimensions_valid, pricing_status, cost_usd) AS (
                    VALUES
                        (now()-interval '20 minutes','STARTED','AMBIGUOUS',NULL,NULL,NULL,NULL),
                        (now()-interval '1 minute','STARTED','AMBIGUOUS',NULL,NULL,NULL,NULL),
                        (now()-interval '1 hour','AMBIGUOUS','AMBIGUOUS',NULL,NULL,NULL,NULL),
                        (now()-interval '1 hour','FAILED','KNOWN_NOT_EXECUTED',NULL,NULL,NULL,NULL),
                        (now()-interval '1 hour','FAILED','KNOWN_REJECTED',NULL,NULL,NULL,NULL),
                        (now()-interval '1 hour','SUCCEEDED','KNOWN_EXECUTED','AVAILABLE',true,'PRICED',0.1),
                        (now()-interval '1 hour','SUCCEEDED','KNOWN_EXECUTED','AVAILABLE',false,'PRICED',0.1),
                        (now()-interval '25 hours','AMBIGUOUS','AMBIGUOUS',NULL,NULL,NULL,NULL),
                        (now()+interval '1 hour','AMBIGUOUS','AMBIGUOUS',NULL,NULL,NULL,NULL)
                )
                INSERT INTO public.model_execution_attempts
                    (id, execution_plan_id, attempt_number, provider_attempt_id,
                     provider_type, provider_configuration_ref, provider_configuration_version,
                     deployment_ref, deployment_version, requested_physical_model,
                     retry_safety, fallback_safety, created_at,
                     started_at, outcome, outcome_certainty, usage_status,
                     specialized_dimensions_valid, pricing_status, cost_usd)
                SELECT gen_random_uuid(), gen_random_uuid(), 1, gen_random_uuid(),
                       'openai', 'fixture:openai', 'fixture-v1',
                       'fixture:openai:gpt-test', 'fixture-v1', 'gpt-test',
                       'SAME_TARGET_RETRY_FORBIDDEN', 'FALLBACK_FORBIDDEN', started_at,
                       started_at, outcome, outcome_certainty, usage_status,
                       specialized_dimensions_valid, pricing_status, cost_usd
                FROM fixture
                """);
        var registry = new SimpleMeterRegistry();
        try {
            var monitor = new ModelAttemptMonitor(ds, registry, Clock.systemUTC());
            var healthy = registry.get("safeai.ai.attempt.observation.healthy").gauge();
            var stalled = registry.get("safeai.ai.attempt.stalled").gauge();
            var ambiguous = registry.get("safeai.ai.attempt.ambiguous").gauge();
            var unknown = registry.get("safeai.ai.attempt.unknown.cost").gauge();
            var timestamp = registry.get("safeai.ai.attempt.last.observation.timestamp").gauge();
            var errors = registry.get("safeai.ai.attempt.observation.errors").counter();
            assertThat(healthy.value()).isZero();
            monitor.observe();
            assertThat(healthy.value()).isEqualTo(1);
            assertThat(stalled.value()).isEqualTo(1);
            assertThat(ambiguous.value()).isEqualTo(1);
            assertThat(unknown.value()).isEqualTo(3);
            double lastSuccess = timestamp.value();
            assertThat(lastSuccess).isPositive();
            jdbc.execute("""
                    INSERT INTO public.model_execution_attempts
                        (id, execution_plan_id, attempt_number, provider_attempt_id,
                         provider_type, provider_configuration_ref, provider_configuration_version,
                         deployment_ref, deployment_version, requested_physical_model,
                         retry_safety, fallback_safety, created_at,
                         started_at, outcome, outcome_certainty)
                    SELECT gen_random_uuid(), gen_random_uuid(), 1, gen_random_uuid(),
                           'openai', 'fixture:openai', 'fixture-v1',
                           'fixture:openai:gpt-test', 'fixture-v1', 'gpt-test',
                           'SAME_TARGET_RETRY_FORBIDDEN', 'FALLBACK_FORBIDDEN', now(),
                           now(), 'STARTED', 'AMBIGUOUS'
                    FROM generate_series(1,100001)
                    """);
            monitor.observe();
            assertThat(healthy.value()).isZero();
            assertThat(stalled.value()).isNaN();
            assertThat(ambiguous.value()).isNaN();
            assertThat(unknown.value()).isNaN();
            assertThat(timestamp.value()).isEqualTo(lastSuccess);
            assertThat(errors.count()).isEqualTo(1);
            jdbc.execute("TRUNCATE public.model_execution_attempts");
            monitor.observe();
            assertThat(healthy.value()).isEqualTo(1);
            assertThat(unknown.value()).isZero();
            jdbc.execute("DROP TABLE public.model_execution_attempts");
            monitor.observe();
            assertThat(healthy.value()).isZero();
            assertThat(unknown.value()).isNaN();
            assertThat(errors.count()).isEqualTo(2);
        } finally {
            registry.close();
            jdbc.execute("DROP TABLE IF EXISTS public.model_execution_attempts");
        }
    }

    @Test
    @Timeout(30)
    void concurrentFailureCannotOverwriteOrLoseLastSuccessfulTimestamp() throws Exception {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(ds);
        createFixture(jdbc);
        var registry = new SimpleMeterRegistry();
        var executor = Executors.newFixedThreadPool(2);
        var firstAtPublication = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var successfulTime = Instant.parse("2026-11-22T09:00:00Z");
        try {
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenAnswer(invocation -> {
                firstAtPublication.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to publish the sample");
                }
                return successfulTime;
            });
            var monitor = new ModelAttemptMonitor(ds, registry, clock);
            var healthy = registry.get("safeai.ai.attempt.observation.healthy").gauge();
            var timestamp = registry.get("safeai.ai.attempt.last.observation.timestamp").gauge();
            var errors = registry.get("safeai.ai.attempt.observation.errors").counter();

            var first = executor.submit(monitor::observe);
            assertThat(firstAtPublication.await(5, TimeUnit.SECONDS)).isTrue();
            // The first SQL query completed. Its result is ready but not yet published.
            jdbc.execute("DROP TABLE public.model_execution_attempts");
            var second = executor.submit(() -> {
                secondStarted.countDown();
                monitor.observe();
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            // Scrape stays lock-free while the first observation holds the writer lock.
            assertThat(healthy.value()).isZero();
            assertThat(timestamp.value()).isZero();
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(healthy.value()).isZero();
            assertThat(timestamp.value()).isEqualTo((double) successfulTime.getEpochSecond());
            assertThat(errors.count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.attempt.stalled").gauge().value()).isNaN();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                registry.close();
                jdbc.execute("DROP TABLE IF EXISTS public.model_execution_attempts");
            }
        }
    }

    private static void createFixture(JdbcTemplate jdbc) {
        // Isolated database per Testcontainers class: no production migration or trigger is altered.
        jdbc.execute("""
                CREATE TABLE public.model_execution_attempts (
                    id uuid PRIMARY KEY,
                    execution_plan_id uuid NOT NULL,
                    attempt_number integer NOT NULL CHECK (attempt_number >= 1),
                    provider_attempt_id uuid NOT NULL UNIQUE,
                    provider_type varchar(32) NOT NULL,
                    provider_configuration_ref varchar(255) NOT NULL,
                    provider_configuration_version varchar(64) NOT NULL,
                    deployment_ref varchar(255) NOT NULL,
                    deployment_version varchar(64) NOT NULL,
                    requested_physical_model varchar(100) NOT NULL,
                    retry_safety varchar(40) NOT NULL,
                    fallback_safety varchar(32) NOT NULL,
                    created_at timestamptz NOT NULL,
                    started_at timestamptz NOT NULL,
                    outcome varchar(24) NOT NULL,
                    outcome_certainty varchar(32) NOT NULL,
                    usage_status varchar(32),
                    specialized_dimensions_valid boolean,
                    pricing_status varchar(32),
                    cost_usd numeric(30,12),
                    UNIQUE (execution_plan_id, attempt_number),
                    CHECK (created_at <= started_at)
                );
                CREATE INDEX idx_model_execution_attempts_started_fixture
                    ON public.model_execution_attempts (started_at);
                """);
    }
}
