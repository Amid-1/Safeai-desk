package ru.safeai.gateway.ratelimit;

public enum RateLimitDecision {
    ALLOWED,
    FIRST_EXCEEDED,
    SECOND_EXCEEDED,
    BOTH_EXCEEDED;

    public boolean isAllowed() {
        return this == ALLOWED;
    }

    public boolean isFirstExceeded() {
        return this == FIRST_EXCEEDED
                || this == BOTH_EXCEEDED;
    }

    public boolean isSecondExceeded() {
        return this == SECOND_EXCEEDED
                || this == BOTH_EXCEEDED;
    }
}
