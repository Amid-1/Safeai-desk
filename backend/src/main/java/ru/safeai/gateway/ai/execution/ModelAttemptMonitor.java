package ru.safeai.gateway.ai.execution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded operational snapshot of physical attempts started in the last 24h.
 * Not the authoritative financial ledger; no SQL is executed by a scrape.
 */
@Component
final class ModelAttemptMonitor {
    private static final System.Logger LOGGER = System.getLogger(ModelAttemptMonitor.class.getName());
    private static final int MAX_ROWS = 100_000;
    static final String SQL = """
            WITH recent AS MATERIALIZED (
                SELECT started_at, outcome, outcome_certainty, usage_status,
                       specialized_dimensions_valid, pricing_status, cost_usd
                FROM public.model_execution_attempts
                WHERE started_at >= statement_timestamp() - interval '24 hours'
                  AND started_at <= statement_timestamp()
                ORDER BY started_at DESC
                LIMIT 100001
            )
            SELECT count(*) AS sampled,
                   count(*) FILTER (WHERE outcome = 'STARTED'
                       AND started_at < statement_timestamp() - interval '15 minutes') AS stalled,
                   count(*) FILTER (WHERE outcome = 'AMBIGUOUS') AS ambiguous,
                   count(*) FILTER (WHERE outcome <> 'STARTED'
                       AND (usage_status = 'AVAILABLE'
                           AND specialized_dimensions_valid IS TRUE
                           AND pricing_status IN ('PRICED', 'FREE')
                           AND cost_usd IS NOT NULL) IS NOT TRUE
                       AND (outcome = 'FAILED'
                           AND outcome_certainty = 'KNOWN_NOT_EXECUTED') IS NOT TRUE) AS unknown_cost
            FROM recent
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter errors;
    private final AtomicReference<Observation> observation =
            new AtomicReference<>(Observation.unavailable(0));
    // Only the synchronized observation writer accesses this flag.
    private boolean failing;

    private record Sample(long sampled, long stalled, long ambiguous, long unknownCost) {}

    private record Observation(double stalled, double ambiguous, double unknownCost,
                               long timestamp, boolean healthy) {
        static Observation unavailable(long timestamp) {
            return new Observation(Double.NaN, Double.NaN, Double.NaN, timestamp, false);
        }
    }

    ModelAttemptMonitor(DataSource dataSource, MeterRegistry registry, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(3);
        this.clock = Objects.requireNonNull(clock);
        this.errors = registry.counter("safeai.ai.attempt.observation.errors");
        Gauge.builder("safeai.ai.attempt.stalled", this, m -> m.observation.get().stalled()).register(registry);
        Gauge.builder("safeai.ai.attempt.ambiguous", this, m -> m.observation.get().ambiguous()).register(registry);
        Gauge.builder("safeai.ai.attempt.unknown.cost", this, m -> m.observation.get().unknownCost()).register(registry);
        Gauge.builder("safeai.ai.attempt.observation.healthy", this,
                m -> m.observation.get().healthy() ? 1 : 0).register(registry);
        Gauge.builder("safeai.ai.attempt.last.observation.timestamp", this,
                m -> m.observation.get().timestamp()).register(registry);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    synchronized void observe() {
        try {
            Sample sample = Objects.requireNonNull(jdbc.queryForObject(SQL, (rs, row) ->
                    new Sample(rs.getLong("sampled"), rs.getLong("stalled"),
                            rs.getLong("ambiguous"), rs.getLong("unknown_cost"))));
            if (sample.sampled() > MAX_ROWS) {
                throw new IllegalStateException("Physical observation row bound exceeded");
            }
            observation.set(new Observation(sample.stalled(), sample.ambiguous(), sample.unknownCost(),
                    clock.instant().getEpochSecond(), true));
            failing = false;
        } catch (RuntimeException failure) {
            observation.updateAndGet(previous -> Observation.unavailable(previous.timestamp()));
            errors.increment();
            if (!failing) {
                LOGGER.log(System.Logger.Level.WARNING, "Physical attempt observation unavailable: "
                        + failure.getClass().getSimpleName());
                failing = true;
            }
        }
    }
}
