package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.usage.config.UsageProperties;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Executes usage reports with admission control local to this application
 * instance.
 *
 * <p>{@code safeai.usage.max-concurrent-reports} is a per-JVM/per-replica
 * limit, not a cluster-wide limit. With N replicas, the theoretical
 * cluster-wide maximum is N multiplied by the configured value. A true
 * cluster-wide ceiling requires distributed admission control.</p>
 */
@Component
public class UsageReportExecutor {

    private static final System.Logger LOGGER =
            System.getLogger(UsageReportExecutor.class.getName());

    private final Semaphore permits;
    private final MeterRegistry meterRegistry;
    private final long slowQueryThresholdNanos;
    private final ConcurrentMap<String, Timer> timers =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rejectedCounters =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> timeoutCounters =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCounters =
            new ConcurrentHashMap<>();

    public UsageReportExecutor(
            UsageProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.permits = new Semaphore(
                properties.maxConcurrentReports(),
                true
        );
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry не должен быть null"
        );
        this.slowQueryThresholdNanos = properties
                .slowQueryThreshold()
                .toNanos();

        try {
            meterRegistry.gauge(
                    "safeai.usage.reports.available_permits",
                    permits,
                    Semaphore::availablePermits
            );
        } catch (RuntimeException exception) {
            logMetricsFailure(
                    "executor",
                    "available-permits-gauge",
                    exception
            );
        }
    }

    public <T> T execute(
            String report,
            Supplier<T> action
    ) {
        String normalizedReport = requireReport(report);
        Objects.requireNonNull(action, "action не должен быть null");

        if (!permits.tryAcquire()) {
            incrementSafely(
                    normalizedReport,
                    "rejected",
                    () -> rejectedCounter(normalizedReport)
            );
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Слишком много одновременных usage-отчётов"
            );
        }

        long startedAt = System.nanoTime();
        Timer.Sample sample = startSampleSafely(normalizedReport);

        try {
            return action.get();
        } catch (QueryTimeoutException exception) {
            incrementSafely(
                    normalizedReport,
                    "timeout",
                    () -> timeoutCounter(normalizedReport)
            );
            throw exception;
        } catch (RuntimeException exception) {
            incrementSafely(
                    normalizedReport,
                    "error",
                    () -> errorCounter(normalizedReport)
            );
            throw exception;
        } finally {
            long durationNanos = System.nanoTime() - startedAt;

            // Admission control must never leak a permit even if metrics
            // registration/recording fails unexpectedly.
            permits.release();
            stopSampleSafely(
                    normalizedReport,
                    sample
            );

            if (durationNanos >= slowQueryThresholdNanos) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Slow usage report: report={0}, durationMs={1}",
                        normalizedReport,
                        Duration.ofNanos(durationNanos).toMillis()
                );
            }
        }
    }

    private Timer.Sample startSampleSafely(String report) {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            logMetricsFailure(report, "start-timer", exception);
            return null;
        }
    }

    private void stopSampleSafely(
            String report,
            Timer.Sample sample
    ) {
        if (sample == null) {
            return;
        }

        try {
            sample.stop(timer(report));
        } catch (RuntimeException exception) {
            logMetricsFailure(report, "stop-timer", exception);
        }
    }

    private void incrementSafely(
            String report,
            String metric,
            Supplier<Counter> counterSupplier
    ) {
        try {
            counterSupplier.get().increment();
        } catch (RuntimeException exception) {
            logMetricsFailure(report, metric, exception);
        }
    }

    private void logMetricsFailure(
            String report,
            String metric,
            RuntimeException exception
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Usage metrics failure: report={0}, metric={1}, error={2}",
                report,
                metric,
                exception.toString()
        );
    }

    private Timer timer(String report) {
        return timers.computeIfAbsent(
                report,
                key -> Timer.builder("safeai.usage.report.duration")
                        .tag("report", key)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
        );
    }

    private Counter rejectedCounter(String report) {
        return rejectedCounters.computeIfAbsent(
                report,
                key -> counter(
                        "safeai.usage.reports.rejected",
                        key
                )
        );
    }

    private Counter timeoutCounter(String report) {
        return timeoutCounters.computeIfAbsent(
                report,
                key -> counter(
                        "safeai.usage.reports.timeout",
                        key
                )
        );
    }

    private Counter errorCounter(String report) {
        return errorCounters.computeIfAbsent(
                report,
                key -> counter(
                        "safeai.usage.reports.error",
                        key
                )
        );
    }

    private Counter counter(String name, String report) {
        return Counter.builder(name)
                .tag("report", report)
                .register(meterRegistry);
    }

    private String requireReport(String report) {
        if (report == null || report.isBlank()) {
            throw new IllegalArgumentException(
                    "report не должен быть пустым"
            );
        }

        String normalized = report.trim();

        if (normalized.length() > 64) {
            throw new IllegalArgumentException(
                    "report превышает 64 символа"
            );
        }

        return normalized;
    }
}
