package ru.safeai.gateway.knowledge.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only, low-cardinality visibility into deletion evidence. This works
 * when all destructive flags are OFF and never claims/renews worker leases.
 * A stale last-success gauge is an alert, not a silently healthy zero.
 */
@Component
public final class KnowledgeErasureObservability {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeErasureObservability.class);
    private static final List<String> STATES = List.of(
            "DELETING", "NEEDS_REVIEW", "BLOCKED_BY_RETENTION", "DELETED");
    private static final List<String> SCOPES = List.of("document", "base");
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> counts = new HashMap<>();
    private final Map<String, AtomicLong> oldestAges = new HashMap<>();
    private final Map<String, AtomicLong> retries = new HashMap<>();
    private final AtomicLong lastSuccessfulRefreshEpochSeconds = new AtomicLong(0);
    private final Counter refreshFailures;

    public KnowledgeErasureObservability(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String scope : SCOPES) {
            for (String state : STATES) {
                String key = scope + ":" + state;
                AtomicLong count = new AtomicLong();
                AtomicLong age = new AtomicLong();
                AtomicLong retry = new AtomicLong();
                counts.put(key, count);
                oldestAges.put(key, age);
                retries.put(key, retry);
                Gauge.builder("safeai.knowledge.erasure.operations", count, AtomicLong::get)
                        .description("Number of erasure operations by scope and state")
                        .tags("scope", scope, "state", state).register(registry);
                Gauge.builder("safeai.knowledge.erasure.oldest.age.seconds", age, AtomicLong::get)
                        .description("Age of oldest operation by scope and state in seconds")
                        .tags("scope", scope, "state", state).register(registry);
                Gauge.builder("safeai.knowledge.erasure.retries", retry, AtomicLong::get)
                        .description("Recorded attempts beyond first claim, by scope and state")
                        .tags("scope", scope, "state", state).register(registry);
            }
        }
        Gauge.builder("safeai.knowledge.erasure.metrics.last.success.epoch.seconds",
                        lastSuccessfulRefreshEpochSeconds, AtomicLong::get)
                .description("UTC timestamp of last successful erasure metrics refresh")
                .register(registry);
        this.refreshFailures = Counter.builder("safeai.knowledge.erasure.metrics.refresh.failures")
                .description("Failed attempts to refresh deletion observability")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${safeai.knowledge.erasure.observability.poll-delay:PT1M}")
    public void refresh() {
        try {
            // PostgreSQL reads only; fixed cardinality, no operation/tenant labels.
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT scope, state, count(*) AS quantity,
                           sum(greatest(attempts - 1, 0))::bigint AS retry_count,
                           greatest(0, floor(extract(epoch from
                             (clock_timestamp() - min(created_at)))))::bigint AS oldest_age_seconds
                    FROM (
                        SELECT 'document'::text AS scope, state, created_at, attempts
                          FROM public.knowledge_document_deletions_v78
                        UNION ALL
                        SELECT 'base'::text AS scope, state, created_at, attempts
                          FROM public.knowledge_base_deletions_v82
                    ) ops
                    GROUP BY scope, state
                    """);
            Map<String, Long> freshCounts = new HashMap<>();
            Map<String, Long> freshAges = new HashMap<>();
            Map<String, Long> freshRetries = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String key = row.get("scope") + ":" + row.get("state");
                if (!counts.containsKey(key)) {
                    throw new IllegalStateException("Unexpected erasure metrics scope/state");
                }
                freshCounts.put(key, ((Number) row.get("quantity")).longValue());
                freshAges.put(key, ((Number) row.get("oldest_age_seconds")).longValue());
                freshRetries.put(key, ((Number) row.get("retry_count")).longValue());
            }
            counts.forEach((key, value) -> value.set(freshCounts.getOrDefault(key, 0L)));
            oldestAges.forEach((key, value) -> value.set(freshAges.getOrDefault(key, 0L)));
            retries.forEach((key, value) -> value.set(freshRetries.getOrDefault(key, 0L)));
            lastSuccessfulRefreshEpochSeconds.set(Instant.now().getEpochSecond());
        } catch (RuntimeException exception) {
            // Keep the last successful data and expose freshness explicitly.
            refreshFailures.increment();
            log.warn("Knowledge erasure observability refresh failed: {}",
                    exception.getClass().getSimpleName());
        }
    }
}
