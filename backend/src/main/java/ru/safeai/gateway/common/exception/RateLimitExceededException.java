package ru.safeai.gateway.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Duration;

public final class RateLimitExceededException extends ApiException {

    @Getter
    private final long retryAfterSeconds;

    public RateLimitExceededException(
            String publicMessage,
            Duration retryAfter
    ) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                publicMessage
        );

        this.retryAfterSeconds = ceilToSeconds(retryAfter);
    }

    private static long ceilToSeconds(Duration duration) {
        if (duration == null
                || duration.isZero()
                || duration.isNegative()) {
            return 0L;
        }

        try {
            long millis = duration.toMillis();

            return Math.max(
                    1L,
                    Math.addExact(millis, 999L) / 1_000L
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}