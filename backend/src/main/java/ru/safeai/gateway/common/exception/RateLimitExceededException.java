package ru.safeai.gateway.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Duration;

public final class RateLimitExceededException
        extends ApiException {

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

        this.retryAfterSeconds =
                ceilToSeconds(retryAfter);
    }

    /**
     * Округляет положительный Duration вверх до целой секунды без
     * промежуточного перевода всего значения в миллисекунды.
     * Это исключает переполнение Duration.toMillis() для больших значений.
     */
    private static long ceilToSeconds(
            Duration duration
    ) {
        if (duration == null
                || duration.isZero()
                || duration.isNegative()) {
            return 0L;
        }

        long seconds = duration.getSeconds();

        if (duration.getNano() == 0) {
            return Math.max(1L, seconds);
        }

        try {
            return Math.max(
                    1L,
                    Math.addExact(seconds, 1L)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
