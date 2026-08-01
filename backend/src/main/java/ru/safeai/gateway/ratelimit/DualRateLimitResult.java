package ru.safeai.gateway.ratelimit;

import java.util.Objects;

public record DualRateLimitResult(
        RateLimitDecision decision,
        long firstCount,
        long secondCount,
        long firstTtlSeconds,
        long secondTtlSeconds,
        boolean firstExceededNotification,
        boolean secondExceededNotification
) {
    public DualRateLimitResult {
        Objects.requireNonNull(
                decision,
                "decision не должен быть null"
        );

        requireNonNegative(firstCount, "firstCount");
        requireNonNegative(secondCount, "secondCount");
        requireNonNegative(firstTtlSeconds, "firstTtlSeconds");
        requireNonNegative(secondTtlSeconds, "secondTtlSeconds");

        if (decision.isAllowed()
                && (firstExceededNotification
                || secondExceededNotification)) {
            throw new IllegalArgumentException(
                    "ALLOWED не может требовать exceeded notification"
            );
        }

        if (!decision.isFirstExceeded()
                && firstExceededNotification) {
            throw new IllegalArgumentException(
                    "firstExceededNotification не соответствует decision"
            );
        }

        if (!decision.isSecondExceeded()
                && secondExceededNotification) {
            throw new IllegalArgumentException(
                    "secondExceededNotification не соответствует decision"
            );
        }
    }

    /**
     * Совместимость со старым шестипараметровым конструктором.
     * Для BOTH_EXCEEDED единый флаг применяется к обеим размерностям.
     */
    public DualRateLimitResult(
            RateLimitDecision decision,
            long firstCount,
            long secondCount,
            long firstTtlSeconds,
            long secondTtlSeconds,
            boolean exceededNotification
    ) {
        this(
                decision,
                firstCount,
                secondCount,
                firstTtlSeconds,
                secondTtlSeconds,
                exceededNotification
                        && decision.isFirstExceeded(),
                exceededNotification
                        && decision.isSecondExceeded()
        );
    }

    public boolean allowed() {
        return decision.isAllowed();
    }

    public boolean notificationRequired() {
        return firstExceededNotification
                || secondExceededNotification;
    }

    /**
     * В ALLOWED состоянии повторять запрос не требуется,
     * поэтому значение равно нулю.
     */
    public long retryAfterSeconds() {
        if (allowed()) {
            return 0L;
        }

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

    private static void requireNonNegative(
            long value,
            String name
    ) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    name + " не должен быть отрицательным"
            );
        }
    }
}
