// backend/src/test/java/ru/safeai/gateway/usage/service/UsageRollupMonitorPostgresIntegrationTest.java
package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.usage.config.UsageJdbcClients;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@Testcontainers
class UsageRollupMonitorPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void realSqlCountsOnlyPastUtcDaysAndHandlesMissingWatermarkAndRecovery() throws Exception {
        var source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var registry = new SimpleMeterRegistry();
        try (var connection = source.getConnection()) {
            var jdbc = createFixture(connection);
            var clients = mock(UsageJdbcClients.class);
            when(clients.rollup()).thenReturn(jdbc);
            var instant = Instant.parse("2026-11-21T11:00:00Z");
            var monitor = new UsageRollupMonitor(clients, registry, Clock.fixed(instant, ZoneOffset.UTC));
            var dirty = registry.get("safeai.usage.rollup.dirty.days").gauge();
            var lag = registry.get("safeai.usage.rollup.lag.days").gauge();
            var timestamp = registry.get("safeai.usage.rollup.last.observation.timestamp").gauge();
            var errors = registry.get("safeai.usage.rollup.observation.errors").counter();

            for (String zone : new String[]{"Pacific/Kiritimati", "America/Adak"}) {
                assertThat(jdbc.queryForObject("select set_config('TimeZone', ?, false)",
                        String.class, zone)).isEqualTo(zone);
                jdbc.update("""
                        update public.usage_rollup_state
                        set last_completed_date = (statement_timestamp() at time zone 'UTC')::date - 3
                        where job_name = 'usage-daily-rollup'
                        """);
                String countSql = """
                        select count(*) from public.usage_dirty_dates
                        where usage_date < (statement_timestamp() at time zone 'UTC')::date
                        """;
                long before = Objects.requireNonNull(jdbc.queryForObject(countSql, Long.class),
                        "Dirty-day count before observation is NULL");
                monitor.observe();
                long after = Objects.requireNonNull(jdbc.queryForObject(countSql, Long.class),
                        "Dirty-day count after observation is NULL");
                // At UTC midnight the observed value can be from either side of the boundary.
                assertThat(dirty.value()).isIn((double) before, (double) after);
                long actualLag = Objects.requireNonNull(jdbc.queryForObject("""
                        select (statement_timestamp() at time zone 'UTC')::date - 1 - last_completed_date
                        from public.usage_rollup_state where job_name = 'usage-daily-rollup'
                        """, Long.class), "Rollup lag is NULL");
                assertThat(lag.value()).isBetween(2.0, (double) actualLag);
                assertThat(timestamp.value()).isEqualTo((double) instant.getEpochSecond());
                assertThat(errors.count()).isZero();
            }
            jdbc.update("""
                    update public.usage_rollup_state set last_completed_date = null
                    where job_name = 'usage-daily-rollup'
                    """);
            monitor.observe();
            assertThat(lag.value()).isEqualTo(-1);
            assertThat(errors.count()).isZero();
            jdbc.update("delete from public.usage_rollup_state where job_name = 'usage-daily-rollup'");
            monitor.observe();
            assertThat(dirty.value()).isNaN();
            assertThat(lag.value()).isNaN();
            assertThat(timestamp.value()).isEqualTo((double) instant.getEpochSecond());
            assertThat(errors.count()).isEqualTo(1);
            jdbc.update("""
                    insert into public.usage_rollup_state (job_name, last_completed_date)
                    values ('usage-daily-rollup', (statement_timestamp() at time zone 'UTC')::date)
                    """);
            monitor.observe();
            assertThat(dirty.value()).isNotNaN();
            assertThat(lag.value()).isZero();
            assertThat(errors.count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    private static JdbcTemplate createFixture(Connection connection) {
        // Preserve the same physical connection for session TimeZone and all observations.
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("""
                create table public.usage_dirty_dates (
                    usage_date date primary key,
                    revision bigint not null default 1 check (revision > 0),
                    updated_at timestamptz not null default now()
                );
                create table public.usage_rollup_state (
                    job_name varchar(100) primary key,
                    last_completed_date date,
                    updated_at timestamptz not null default current_timestamp
                );
                insert into public.usage_dirty_dates (usage_date)
                select (statement_timestamp() at time zone 'UTC')::date + d
                from generate_series(-2, 1) d;
                insert into public.usage_rollup_state (job_name, last_completed_date)
                values ('usage-daily-rollup', (statement_timestamp() at time zone 'UTC')::date - 3),
                       ('other-job', null);
                """);
        return jdbc;
    }
}
