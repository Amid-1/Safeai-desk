package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitMetricsTest {
    @Test
    void exportsCanonicalPrometheusNamesAndHistogram() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new RateLimitMetrics(registry);
            metrics.finishRedisOperation(metrics.startRedisOperation(), "login", "check", "success");
            String scrape = registry.scrape();
            assertThat(scrape).contains("safeai_rate_limit_allowed_total", "safeai_rate_limit_rejected_total",
                    "safeai_rate_limit_unavailable_total", "safeai_rate_limit_audit_publish_failed_total",
                    "safeai_rate_limit_redis_duration_seconds_bucket", "safeai_rate_limit_redis_duration_seconds_count");
        } finally {
            registry.close();
        }
    }

    @Test
    void registersIdleCountersAndBoundsAllCallerLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new RateLimitMetrics(registry);
            for (String type : new String[]{"login", "refresh", "ai_message"}) {
                assertThat(registry.get("safeai.rate.limit.allowed").tag("type", type).counter().count()).isZero();
                assertThat(registry.get("safeai.rate.limit.unavailable").tag("type", type).counter().count()).isZero();
            }
            metrics.recordAllowed("login");
            metrics.recordRejected("login", "capacity");
            metrics.recordUnavailable("refresh");
            metrics.recordAuditPublishFailed("ai_message");
            assertThat(registry.get("safeai.rate.limit.allowed").tag("type", "login").counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.rate.limit.rejected").tags("type", "login", "dimension", "capacity")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.rate.limit.unavailable").tag("type", "refresh").counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.rate.limit.audit.publish.failed").tag("type", "ai_message")
                    .counter().count()).isEqualTo(1);
            for (int i = 0; i < 1000; i++) {
                String privateValue = "private-test-value-" + i;
                metrics.recordAllowed(privateValue);
                metrics.recordRejected(privateValue, privateValue);
                metrics.recordUnavailable(privateValue);
                metrics.recordAuditPublishFailed(privateValue);
                metrics.finishRedisOperation(metrics.startRedisOperation(), privateValue, privateValue, privateValue);
            }
            assertThat(registry.get("safeai.rate.limit.allowed").tag("type", "unknown").counter().count()).isEqualTo(1000);
            assertThat(registry.get("safeai.rate.limit.rejected").tags("type", "unknown", "dimension", "unknown")
                    .counter().count()).isEqualTo(1000);
            assertThat(registry.get("safeai.rate.limit.unavailable").tag("type", "unknown").counter().count()).isEqualTo(1000);
            assertThat(registry.get("safeai.rate.limit.audit.publish.failed").tag("type", "unknown")
                    .counter().count()).isEqualTo(1000);
            assertThat(registry.get("safeai.rate.limit.redis.duration").tags("type", "unknown", "operation", "unknown",
                    "outcome", "unknown").timer().count()).isEqualTo(1000);
            assertThat(registry.getMeters()).hasSize(40).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag ->
                            assertThat(tag.getValue()).doesNotContain("private-test-value")));
        } finally {
            registry.close();
        }
    }
}
