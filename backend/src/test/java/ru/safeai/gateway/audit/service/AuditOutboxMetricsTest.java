package ru.safeai.gateway.audit.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditOutboxMetricsTest {

    @Test
    void failedRefreshDoesNotAdvanceHeartbeatOrReplacePreviousGauge() {
        var repository = mock(AuditOutboxRepository.class);
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new AuditOutboxMetrics(repository, registry, clock);
            var heartbeat = registry.get("safeai.audit.outbox.metrics.last.success.timestamp").gauge();
            assertThat(heartbeat.value()).isZero();
            when(repository.countByDeadLetteredAtIsNull()).thenReturn(2L, 9L);
            when(repository.findOldestPendingCreatedAt()).thenReturn(Optional.empty())
                    .thenThrow(new IllegalStateException("database error"));
            metrics.refreshBacklog();
            assertThat(heartbeat.value()).isEqualTo((double) now.getEpochSecond());
            when(clock.instant()).thenReturn(now.plusSeconds(30));
            metrics.refreshBacklog();
            assertThat(heartbeat.value()).isEqualTo((double) now.getEpochSecond());
            assertThat(registry.get("safeai.audit.outbox.pending").gauge().value()).isEqualTo(2.0);
            assertThat(registry.get("safeai.audit.outbox.metrics.refresh.failures").counter().count())
                    .isEqualTo(1.0);
            metrics.recordIntegrityConflict();
            assertThat(registry.get("safeai.audit.outbox.integrity.conflicts").counter().count())
                    .isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }
}
