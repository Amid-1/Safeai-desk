package ru.safeai.gateway.ratelimit;

import java.time.Duration;

/** Internal Redis allocation refusal, not a Redis outage or an identity-specific rejection. */
final class AnonymousCapacityExceededException extends RuntimeException {
    private final Duration retryAfter;

    AnonymousCapacityExceededException(Duration retryAfter) {
        super("Anonymous rate-limit allocation capacity exhausted");
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
