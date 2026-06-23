package ru.safeai.gateway.ratelimit;

public record RateLimitResult(
        long count,
        long ttlSeconds
) {
}