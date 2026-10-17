package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.usage.config.UsageProperties;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Admission is acquired BEFORE any usage report transaction or connection.
 * In production a PostgreSQL transaction-scoped advisory slot additionally
 * limits concurrent reports across replicas sharing the same database.
 */
@Component
public class UsageReportExecutor {

    private static final System.Logger LOGGER =
            System.getLogger(UsageReportExecutor.class.getName());

    // Dedicated cluster-wide namespace. Never reuse a rollup or chat lock key.
    private static final long REPORT_LOCK_NAMESPACE = 8_026_110_000L;
    private static final String TRY_GLOBAL_SLOT =
            "select pg_try_advisory_xact_lock(?)";

    private final Semaphore permits;
    private final int globalSlots;
    private final TransactionTemplate readTransaction;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final long slowQueryThresholdNanos;
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> timeoutCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    /** Production constructor: requires the actual JDBC transaction manager. */
    @Autowired
    public UsageReportExecutor(
            UsageProperties properties,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc
    ) {
        this(properties, meterRegistry, transactionManager, jdbc, true);
    }

    /** Legacy unit-test constructor only; production uses the @Autowired constructor. */
    public UsageReportExecutor(UsageProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, null, null, false);
    }

    private UsageReportExecutor(
            UsageProperties properties,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc,
            boolean useDatabaseFence
    ) {
        Objects.requireNonNull(properties, "properties");
        this.permits = new Semaphore(properties.maxConcurrentReports(), true);
        this.globalSlots = properties.maxConcurrentReports();
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.slowQueryThresholdNanos = properties.slowQueryThreshold().toNanos();

        if (useDatabaseFence) {
            this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
            TransactionTemplate template = new TransactionTemplate(
                    Objects.requireNonNull(transactionManager, "transactionManager")
            );
            template.setReadOnly(true);
            template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.readTransaction = template;
        } else {
            this.jdbc = null;
            this.readTransaction = null;
        }

        try {
            meterRegistry.gauge(
                    "safeai.usage.reports.available_permits",
                    permits,
                    Semaphore::availablePermits
            );
        } catch (RuntimeException exception) {
            logMetricsFailure("executor", "available-permits-gauge", exception);
        }
    }

    public <T> T execute(String report, Supplier<T> action) {
        String normalizedReport = requireReport(report);
        Objects.requireNonNull(action, "action не должен быть null");

        if (!permits.tryAcquire()) {
            rejected(normalizedReport);
            throw tooManyReports();
        }

        long startedAt = System.nanoTime();
        Timer.Sample sample = startSampleSafely(normalizedReport);
        try {
            if (readTransaction == null) {
                // Only the explicit two-argument unit-test constructor takes this path.
                return action.get();
            }
            return readTransaction.execute(status -> {
                // With a real JdbcTransactionManager this uses exactly the same
                // transaction connection as subsequent usage JDBC queries.
                if (!acquireGlobalSlot()) {
                    rejected(normalizedReport);
                    throw tooManyReports();
                }
                return action.get();
            });
        } catch (QueryTimeoutException exception) {
            incrementSafely(normalizedReport, "timeout", () -> timeoutCounter(normalizedReport));
            throw exception;
        } catch (RuntimeException exception) {
            incrementSafely(normalizedReport, "error", () -> errorCounter(normalizedReport));
            throw exception;
        } finally {
            long durationNanos = System.nanoTime() - startedAt;
            permits.release();
            stopSampleSafely(normalizedReport, sample);
            if (durationNanos >= slowQueryThresholdNanos) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Slow usage report: report={0}, durationMs={1}",
                        normalizedReport, Duration.ofNanos(durationNanos).toMillis());
            }
        }
    }

    private boolean acquireGlobalSlot() {
        // Each acquired advisory xact lock is held until commit/rollback.
        // Stable ascending slot order avoids lock-order inversions.
        for (int slot = 0; slot < globalSlots; slot++) {
            Boolean acquired = jdbc.queryForObject(
                    TRY_GLOBAL_SLOT,
                    Boolean.class,
                    Math.addExact(REPORT_LOCK_NAMESPACE, slot)
            );
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
        }
        return false;
    }

    private static ResponseStatusException tooManyReports() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Слишком много одновременных usage-отчётов"
        );
    }

    private void rejected(String report) {
        incrementSafely(report, "rejected", () -> rejectedCounter(report));
    }

    private Timer.Sample startSampleSafely(String report) {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            logMetricsFailure(report, "start-timer", exception);
            return null;
        }
    }

    private void stopSampleSafely(String report, Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(timer(report));
        } catch (RuntimeException exception) {
            logMetricsFailure(report, "stop-timer", exception);
        }
    }

    private void incrementSafely(String report, String metric, Supplier<Counter> supplier) {
        try {
            supplier.get().increment();
        } catch (RuntimeException exception) {
            logMetricsFailure(report, metric, exception);
        }
    }

    private void logMetricsFailure(String report, String metric, RuntimeException exception) {
        LOGGER.log(System.Logger.Level.WARNING,
                "Usage metrics failure: report={0}, metric={1}, error={2}",
                report, metric, exception.toString());
    }

    private Timer timer(String report) {
        return timers.computeIfAbsent(report,
                key -> Timer.builder("safeai.usage.report.duration")
                        .tag("report", key)
                        .publishPercentileHistogram()
                        .register(meterRegistry));
    }

    private Counter rejectedCounter(String report) {
        return rejectedCounters.computeIfAbsent(report,
                key -> counter("safeai.usage.reports.rejected", key));
    }

    private Counter timeoutCounter(String report) {
        return timeoutCounters.computeIfAbsent(report,
                key -> counter("safeai.usage.reports.timeout", key));
    }

    private Counter errorCounter(String report) {
        return errorCounters.computeIfAbsent(report,
                key -> counter("safeai.usage.reports.error", key));
    }

    private Counter counter(String name, String report) {
        return Counter.builder(name).tag("report", report).register(meterRegistry);
    }

    private String requireReport(String report) {
        if (report == null || report.isBlank()) {
            throw new IllegalArgumentException("report не должен быть пустым");
        }
        String normalized = report.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("report превышает 64 символа");
        }
        return normalized;
    }
}
