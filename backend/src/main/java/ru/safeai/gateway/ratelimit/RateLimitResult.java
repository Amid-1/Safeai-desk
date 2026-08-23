package ru.safeai.gateway.ratelimit;

/**
 * Result of a single-key fixed-window rate-limit increment.
 *
 * @param count current counter value after increment
 * @param ttlSeconds remaining window TTL rounded up to seconds
 */
public record RateLimitResult(
        long count,
        long ttlSeconds
) {

    public RateLimitResult {
        if (count < 0L) {
            throw new IllegalArgumentException(
                    "count не должен быть отрицательным"
            );
        }

        if (ttlSeconds < 1L) {
            throw new IllegalArgumentException(
                    "ttlSeconds должен быть положительным"
            );
        }
    }
}
