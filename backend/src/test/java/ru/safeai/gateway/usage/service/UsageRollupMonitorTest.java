// backend/src/test/java/ru/safeai/gateway/usage/service/UsageRollupMonitorTest.java
package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.safeai.gateway.usage.config.UsageJdbcClients;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UsageRollupMonitorTest {
    @Test
    void failedObservationIsUnknownAndRecoveryRestoresFreshDataWithoutScrapeSql() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var clients = mock(UsageJdbcClients.class);
        var now = Instant.parse("2026-11-21T11:00:00Z");
        var recoveredAt = now.plusSeconds(240);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now, recoveredAt);
        when(clients.rollup()).thenReturn(jdbc);
        var registry = new SimpleMeterRegistry();
        try (ResultSet rs = mock(ResultSet.class)) {
            when(rs.getLong("dirty")).thenReturn(3L, 0L);
            when(rs.getLong("lag")).thenReturn(2L, 0L);
            doAnswer(invocation -> invocation.<RowMapper<?>>getArgument(1).mapRow(rs, 0))
                    .doThrow(new DataAccessResourceFailureException("unavailable"))
                    .doThrow(new EmptyResultDataAccessException(1))
                    .doReturn(null)
                    .doAnswer(invocation -> invocation.<RowMapper<?>>getArgument(1).mapRow(rs, 0))
                    .when(jdbc).queryForObject(anyString(), ArgumentMatchers.<RowMapper<Object>>any());
            var monitor = new UsageRollupMonitor(clients, registry, clock);
            var dirty = registry.get("safeai.usage.rollup.dirty.days").gauge();
            var lag = registry.get("safeai.usage.rollup.lag.days").gauge();
            var timestamp = registry.get("safeai.usage.rollup.last.observation.timestamp").gauge();
            var errors = registry.get("safeai.usage.rollup.observation.errors").counter();
            assertThat(dirty.value()).isNaN();
            assertThat(lag.value()).isNaN();
            assertThat(timestamp.value()).isZero();
            assertThat(errors.count()).isZero();
            verifyNoInteractions(jdbc);
            monitor.observe();
            assertThat(dirty.value()).isEqualTo(3);
            assertThat(lag.value()).isEqualTo(2);
            assertThat(timestamp.value()).isEqualTo((double) now.getEpochSecond());
            for (int failure = 1; failure <= 3; failure++) {
                monitor.observe();
                assertThat(dirty.value()).isNaN();
                assertThat(lag.value()).isNaN();
                assertThat(timestamp.value()).isEqualTo((double) now.getEpochSecond());
                assertThat(errors.count()).isEqualTo(failure);
            }
            monitor.observe();
            assertThat(dirty.value()).isZero();
            assertThat(lag.value()).isZero();
            assertThat(timestamp.value()).isEqualTo((double) recoveredAt.getEpochSecond());
            assertThat(errors.count()).isEqualTo(3);
            verify(jdbc, times(5)).queryForObject(anyString(), ArgumentMatchers.<RowMapper<Object>>any());
            verifyNoMoreInteractions(jdbc);
            verify(clock, times(2)).instant();
        } finally {
            registry.close();
        }
    }
}
