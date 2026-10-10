package ru.safeai.gateway.ratelimit.exception;

public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
