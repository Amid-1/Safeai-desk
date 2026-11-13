package ru.safeai.gateway.ratelimit.exception;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.LongAdder;

final class RateLimitUnavailableLogger {

    private static final long LOG_INTERVAL_NANOS =
            TimeUnit.MINUTES.toNanos(1);

    private final AtomicReference<Long> nextLogNanos = new AtomicReference<>();
    private final LongSupplier nanoTime;
    private final LongAdder suppressedLogs = new LongAdder();

    RateLimitUnavailableLogger() {
        this(System::nanoTime);
    }

    RateLimitUnavailableLogger(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    void log(
            Logger logger,
            RateLimitUnavailableException exception
    ) {
        long now = nanoTime.getAsLong();

        while (true) {
            Long next = nextLogNanos.get();
            if (next != null && now - next < 0L) {
                suppressedLogs.increment();
                return;
            }

            long candidate = now + LOG_INTERVAL_NANOS;
            if (!nextLogNanos.compareAndSet(next, candidate)) {
                now = nanoTime.getAsLong();
                continue;
            }

            long suppressed = suppressedLogs.sumThenReset();
            logger.error(
                    "Rate limit service unavailable: failureType={}, suppressedSinceLastLog={}",
                    exception.getCause() == null
                            ? exception.getClass().getName() : exception.getCause().getClass().getName(),
                    suppressed
            );
            return;
        }
    }
}
