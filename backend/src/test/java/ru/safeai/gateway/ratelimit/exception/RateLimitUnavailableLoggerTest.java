package ru.safeai.gateway.ratelimit.exception;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RateLimitUnavailableLoggerTest {

    @Test
    void logsFirstFailureAtNegativeNanoTimeAndNeverPassesExceptionDataToLogger() {
        AtomicLong clock = new AtomicLong(-Duration.ofMinutes(2).toNanos());
        RateLimitUnavailableLogger unavailableLogger = new RateLimitUnavailableLogger(clock::get);
        Logger logger = mock(Logger.class);
        RateLimitUnavailableException failure = new RateLimitUnavailableException(
                "synthetic-secret", new IllegalStateException("nested-synthetic-secret"));
        String template = "Rate limit service unavailable: failureType={}, suppressedSinceLastLog={}";

        unavailableLogger.log(logger, failure);
        verify(logger).error(template, IllegalStateException.class.getName(), 0L);

        unavailableLogger.log(logger, failure);
        verifyNoMoreInteractions(logger);

        clock.addAndGet(Duration.ofMinutes(1).toNanos());
        unavailableLogger.log(logger, failure);
        verify(logger).error(template, IllegalStateException.class.getName(), 1L);
        verifyNoMoreInteractions(logger);
    }
}
