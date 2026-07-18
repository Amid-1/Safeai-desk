package ru.safeai.gateway.ratelimit;

public record DualRateLimitResult(
        RateLimitDecision decision,
        long firstCount,
        long secondCount,
        long firstTtlSeconds,
        long secondTtlSeconds,
        boolean exceededNotification
) {
    public boolean allowed() {
        return decision.isAllowed();
    }

    public long retryAfterSeconds() {
        long retryAfter = 1L;

        if (decision.isFirstExceeded()) {
            retryAfter = Math.max(
                    retryAfter,
                    firstTtlSeconds
            );
        }

        if (decision.isSecondExceeded()) {
            retryAfter = Math.max(
                    retryAfter,
                    secondTtlSeconds
            );
        }

        return retryAfter;
    }
}
