package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Best-effort telemetry. An observability failure must never grant or deny
 * access, change a rate-limit decision, or mask the original Redis error.
 */
@Component
public class RateLimitMetrics {

    private static final System.Logger LOGGER =
            System.getLogger(RateLimitMetrics.class.getName());

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public RateLimitMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry не должен быть null"
        );
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
            sample.stop(
                    Timer.builder("safeai.rate.limit.redis.duration")
                            .description("Redis rate-limit operation duration")
                            .tag("type", tag(type))
                            .tag("operation", tag(operation))
                            .tag("outcome", tag(outcome))
                            .publishPercentileHistogram()
                            .register(meterRegistry)
            );
        } catch (RuntimeException exception) {
            warn("finish", exception);
        }
    }

    public void recordAllowed(String type) {
        record(
                "allowed",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.allowed",
                        "type", tag(type)
                ).increment()
        );
    }

    public void recordRejected(String type, String dimension) {
        record(
                "rejected",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.rejected",
                        "type", tag(type),
                        "dimension", tag(dimension)
                ).increment()
        );
    }

    public void recordUnavailable(String type) {
        record(
                "unavailable",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.unavailable",
                        "type", tag(type)
                ).increment()
        );
    }

    public void recordAuditPublishFailed(String type) {
        record(
                "audit-publish-failed",
                () -> meterRegistry.counter(
                        "safeai.rate.limit.audit.publish.failed",
                        "type", tag(type)
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

    private static String tag(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static void warn(String operation, RuntimeException exception) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Rate-limit metrics failure: operation={0}, error={1}",
                operation,
                exception.toString()
        );
    }
}
