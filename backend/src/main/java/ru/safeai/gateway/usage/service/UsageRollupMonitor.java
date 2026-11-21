// backend/src/main/java/ru/safeai/gateway/usage/service/UsageRollupMonitor.java
package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.usage.config.UsageJdbcClients;

import java.time.Clock;
import java.util.Objects;

/**
 * Мониторинг агрегатов Usage.
 * <p>
 * SQL выполняется по расписанию, а не при сборе метрик Prometheus.
 * Обновление состояния синхронизировано, чтение метрик не блокируется.
 */
@Component
@ConditionalOnProperty(
        name = "safeai.usage.rollup.enabled",
        havingValue = "true",
        matchIfMissing = true
)
final class UsageRollupMonitor {

    private static final System.Logger LOGGER =
            System.getLogger(UsageRollupMonitor.class.getName());

    private final JdbcTemplate jdbc;
    private final Counter observationErrors;
    private final Clock clock;

    private volatile Observation observation =
            new Observation(Double.NaN, Double.NaN, 0);

    // Доступ только внутри синхронизированного метода observe().
    private boolean failing;

    private record Observation(
            double dirty,
            double lagDays,
            long epochSeconds
    ) {
    }

    private record Sample(
            long dirty,
            long lagDays
    ) {
    }

    UsageRollupMonitor(
            UsageJdbcClients clients,
            MeterRegistry meters,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(clients, "clients").rollup();
        this.clock = Objects.requireNonNull(clock, "clock");

        Objects.requireNonNull(meters, "meters");

        this.observationErrors =
                meters.counter("safeai.usage.rollup.observation.errors");

        Gauge.builder(
                        "safeai.usage.rollup.dirty.days",
                        this,
                        monitor -> monitor.observation.dirty()
                )
                .register(meters);

        Gauge.builder(
                        "safeai.usage.rollup.lag.days",
                        this,
                        monitor -> monitor.observation.lagDays()
                )
                .register(meters);

        Gauge.builder(
                        "safeai.usage.rollup.last.observation.timestamp",
                        this,
                        monitor -> monitor.observation.epochSeconds()
                )
                .register(meters);
    }

    @Scheduled(fixedDelay = 60_000)
    synchronized void observe() {

        Observation previous = observation;

        try {
            Sample sample = Objects.requireNonNull(
                    jdbc.queryForObject("""
                            WITH boundary AS (
                                SELECT (statement_timestamp()
                                    AT TIME ZONE 'UTC')::date AS today
                            )
                            SELECT (
                                SELECT count(*)
                                FROM public.usage_dirty_dates
                                WHERE usage_date < b.today
                            ) AS dirty,
                            CASE
                                WHEN s.last_completed_date IS NULL THEN -1
                                ELSE greatest(
                                    0,
                                    b.today - 1 - s.last_completed_date
                                )
                            END AS lag
                            FROM public.usage_rollup_state s
                            CROSS JOIN boundary b
                            WHERE s.job_name = 'usage-daily-rollup'
                            """,
                            (rs, row) -> new Sample(
                                    rs.getLong("dirty"),
                                    rs.getLong("lag")
                            )
                    )
            );

            observation = new Observation(
                    sample.dirty(),
                    sample.lagDays(),
                    clock.instant().getEpochSecond()
            );

            failing = false;

        } catch (RuntimeException exception) {

            // Сохраняем время последнего успешного наблюдения.
            // Устаревшие значения не выдаём за актуальные.
            observation = new Observation(
                    Double.NaN,
                    Double.NaN,
                    previous.epochSeconds()
            );

            observationErrors.increment();

            if (!failing) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Usage rollup observation failed: {0}",
                        exception.getClass().getSimpleName()
                );

                failing = true;
            }
        }
    }
}
