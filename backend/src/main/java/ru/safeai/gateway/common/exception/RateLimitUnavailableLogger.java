package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class RateLimitUnavailableLogger {

    private static final long LOG_INTERVAL_NANOS =
            TimeUnit.MINUTES.toNanos(1);

    private final AtomicLong nextLogNanos = new AtomicLong();
    private final LongAdder suppressedLogs = new LongAdder();

    void log(
            Logger logger,
            RateLimitUnavailableException exception,
            HttpServletRequest request,
            String requestId
    ) {
        long now = System.nanoTime();

        while (true) {
            long next = nextLogNanos.get();
            if (now - next < 0L) {
                suppressedLogs.increment();
                return;
            }

            long candidate = now + LOG_INTERVAL_NANOS;
            if (!nextLogNanos.compareAndSet(next, candidate)) {
                now = System.nanoTime();
                continue;
            }

            long suppressed = suppressedLogs.sumThenReset();
            logger.error(
                    "Rate limit service unavailable: requestId={}, "
                            + "path={}, suppressedSinceLastLog={}",
                    requestId,
                    request.getRequestURI(),
                    suppressed,
                    exception
            );
            return;
        }
    }
}
