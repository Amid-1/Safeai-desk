package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class RateLimitMetrics {

    private static final String UNKNOWN =
            "unknown";

    private final MeterRegistry meterRegistry;

    public RateLimitMetrics(
            MeterRegistry meterRegistry
    ) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry не должен быть null"
        );
    }

    public Timer.Sample startRedisOperation() {
        return Timer.start(meterRegistry);
    }

    public void finishRedisOperation(
            Timer.Sample sample,
            String type,
            String operation,
            String outcome
    ) {
        Objects.requireNonNull(
                sample,
                "sample не должен быть null"
        );

        sample.stop(
                Timer.builder(
                                "safeai.rate.limit.redis.duration"
                        )
                        .description(
                                "Redis rate-limit operation duration"
                        )
                        .tag("type", tag(type))
                        .tag("operation", tag(operation))
                        .tag("outcome", tag(outcome))
                        .publishPercentileHistogram()
                        .register(meterRegistry)
        );
    }

    public void recordAllowed(
            String type
    ) {
        meterRegistry.counter(
                "safeai.rate.limit.allowed",
                "type",
                tag(type)
        ).increment();
    }

    public void recordRejected(
            String type,
            String dimension
    ) {
        meterRegistry.counter(
                "safeai.rate.limit.rejected",
                "type",
                tag(type),
                "dimension",
                tag(dimension)
        ).increment();
    }

    public void recordUnavailable(
            String type
    ) {
        meterRegistry.counter(
                "safeai.rate.limit.unavailable",
                "type",
                tag(type)
        ).increment();
    }

    public void recordAuditPublishFailed(
            String type
    ) {
        meterRegistry.counter(
                "safeai.rate.limit.audit.publish.failed",
                "type",
                tag(type)
        ).increment();
    }

    private String tag(
            String value
    ) {
        return value == null || value.isBlank()
                ? UNKNOWN
                : value;
    }
}
