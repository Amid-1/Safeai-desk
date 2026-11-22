package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort telemetry. An observability failure must never grant or deny
 * access, change a rate-limit decision, or mask the original Redis error.
 */
@Component
public class RateLimitMetrics {

    private static final System.Logger LOGGER =
            System.getLogger(RateLimitMetrics.class.getName());

    private static final String UNKNOWN = "unknown";
    private static final Set<String> TYPES = Set.of("login", "refresh", "ai_message");
    private static final Set<String> DIMENSIONS = Set.of("email", "ip", "both", "user", "organization", "capacity");
    private static final Set<String> OPERATIONS = Set.of("check", "login_success_cleanup");
    private static final Set<String> OUTCOMES = Set.of("success", "error");

    private final MeterRegistry meterRegistry;
    private final AtomicBoolean warningLogged = new AtomicBoolean();

    public RateLimitMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry не должен быть null"
        );
        // Zero-valued counters are observable even before the first request.
        record("initialize", () -> {
            for (String type : TYPES) {
                meterRegistry.counter("safeai.rate.limit.allowed", "type", type);
                meterRegistry.counter("safeai.rate.limit.unavailable", "type", type);
                meterRegistry.counter("safeai.rate.limit.audit.publish.failed", "type", type);
                for (String dimension : DIMENSIONS) {
                    meterRegistry.counter("safeai.rate.limit.rejected", "type", type, "dimension", dimension);
                }
                for (String outcome : OUTCOMES) {
                    timer(type, "check", outcome);
                }
            }
            for (String outcome : OUTCOMES) {
                timer("login", "login_success_cleanup", outcome);
            }
        });
    }

    /**
     * Nullable by design: metric failures must not affect the request.
     */
    public Timer.Sample startRedisOperation() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            warn("start", exception);
            return null;
        }
    }

    public void finishRedisOperation(
            Timer.Sample sample,
            String type,
            String operation,
            String outcome
    ) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(timer(type, operation, outcome));
        } catch (RuntimeException exception) {
            warn("finish", exception);
        }
    }

    private Timer timer(String type, String operation, String outcome) {
        return Timer.builder("safeai.rate.limit.redis.duration")
                .description("Redis rate-limit operation duration")
                .tag("type", tag(type, TYPES))
                .tag("operation", tag(operation, OPERATIONS))
                .tag("outcome", tag(outcome, OUTCOMES))
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void recordAllowed(String type) {
        record(
                "allowed",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.allowed",
                        "type", tag(type, TYPES)
                ).increment()
        );
    }

    public void recordRejected(String type, String dimension) {
        record(
                "rejected",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.rejected",
                        "type", tag(type, TYPES),
                        "dimension", tag(dimension, DIMENSIONS)
                ).increment()
        );
    }

    public void recordUnavailable(String type) {
        record(
                "unavailable",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.unavailable",
                        "type", tag(type, TYPES)
                ).increment()
        );
    }

    public void recordAuditPublishFailed(String type) {
        record(
                "audit-publish-failed",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.audit.publish.failed",
                        "type", tag(type, TYPES)
                ).increment()
        );
    }

    private void record(String operation, Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException exception) {
            warn(operation, exception);
        }
    }

    private static String tag(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : UNKNOWN;
    }

    private void warn(String operation, RuntimeException exception) {
        if (!warningLogged.compareAndSet(false, true)) return;
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Rate-limit metrics failure: operation={0}, error={1}",
                operation,
                exception.getClass().getName()
        );
    }
}
