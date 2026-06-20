package ru.safeai.gateway.common.exception;

import lombok.Getter;

import java.time.Duration;

@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfterSeconds = retryAfter == null
                ? 0
                : Math.max(0, retryAfter.toSeconds());
    }
}