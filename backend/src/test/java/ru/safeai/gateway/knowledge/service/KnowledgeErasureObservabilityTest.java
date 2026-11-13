package ru.safeai.gateway.knowledge.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeErasureObservabilityTest {

    @Test
    void exportsReadOnlyLowCardinalityMetricsWithoutOperationIdentifiers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "scope", "document",
                "state", "NEEDS_REVIEW",
                "quantity", 3L,
                "oldest_age_seconds", 120L, "retry_count", 8L)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            var observer = new KnowledgeErasureObservability(jdbc, registry);
            observer.refresh();
            assertThat(registry.get("safeai.knowledge.erasure.operations")
                    .tags("scope", "document", "state", "NEEDS_REVIEW")
                    .gauge().value()).isEqualTo(3d);
            assertThat(registry.get("safeai.knowledge.erasure.oldest.age.seconds")
                    .tags("scope", "document", "state", "NEEDS_REVIEW")
                    .gauge().value()).isEqualTo(120d);
            assertThat(registry.get("safeai.knowledge.erasure.metrics.last.success.epoch.seconds")
                    .gauge().value()).isGreaterThan(0);
            assertThat(registry.get("safeai.knowledge.erasure.retries")
                    .tags("scope", "document", "state", "NEEDS_REVIEW")
                    .gauge().value()).isEqualTo(8d);
            assertThat(registry.getMeters()).hasSize(26);
            // 2*4*3 gauges, 1 freshness gauge, 1 failure counter.
        } finally {
            registry.close();
        }
    }

    @Test
    void failedRefreshPreservesLastKnownValuesAndSignalsFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("scope", "base", "state", "DELETING",
                        "quantity", 2L, "oldest_age_seconds", 3600L, "retry_count", 2L)))
                .thenThrow(new IllegalStateException("simulated database outage"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            var observer = new KnowledgeErasureObservability(jdbc, registry);
            observer.refresh();
            observer.refresh();
            assertThat(registry.get("safeai.knowledge.erasure.operations")
                    .tags("scope", "base", "state", "DELETING")
                    .gauge().value()).isEqualTo(2d);
            assertThat(registry.get("safeai.knowledge.erasure.metrics.refresh.failures")
                    .counter().count()).isEqualTo(1d);
        } finally {
            registry.close();
        }
    }
}
