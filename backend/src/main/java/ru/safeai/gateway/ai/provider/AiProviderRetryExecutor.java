package ru.safeai.gateway.ai.provider;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import ru.safeai.gateway.ai.dto.AiChatResponse;

import ru.safeai.gateway.ai.exception.AiProviderException;

import java.time.Duration;

import java.util.Objects;

import java.util.UUID;

import java.util.concurrent.ThreadLocalRandom;

import java.util.function.Function;

import java.util.function.Supplier;

@Slf4j

@Component

@RequiredArgsConstructor

public class AiProviderRetryExecutor {

    private final AiRetryProperties properties;

    public AiChatResponse execute(
            String provider,
            String model,
            UUID operationId,
            Duration attemptTimeout,
            Function<AiProviderAttemptContext, AiChatResponse> action
    ) {
        Objects.requireNonNull(
                operationId,
                "operationId не должен быть null"
        );
        Objects.requireNonNull(
                action,
                "action не должен быть null"
        );

        Duration normalizedAttemptTimeout =
                normalizeAttemptTimeout(
                        attemptTimeout
                );

        Duration totalTimeout =
                properties.effectiveTotalTimeout();

        if (!normalizedAttemptTimeout.isZero()
                && normalizedAttemptTimeout.compareTo(totalTimeout) > 0) {
            throw new IllegalStateException(
                    "AI provider attempt timeout "
                            + normalizedAttemptTimeout
                            + " превышает safeai.ai.retry.total-timeout "
                            + totalTimeout
            );
        }

        int maxAttempts = properties.effectiveMaxAttempts();
        Duration backoff = properties.effectiveInitialBackoff();
        long deadlineNanos = safeAdd(
                System.nanoTime(),
                totalTimeout.toNanos()
        );

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            AiProviderAttemptContext context =
                    new AiProviderAttemptContext(
                            operationId,
                            UUID.randomUUID(),
                            attempt,
                            maxAttempts
                    );

            try {
                return action.apply(context);
            } catch (AiProviderException exception) {
                if (!shouldRetry(
                        exception,
                        attempt,
                        maxAttempts
                )) {
                    throw exception;
                }

                Duration delay = retryDelay(exception, backoff);

                if (!fitsDeadline(
                        delay,
                        normalizedAttemptTimeout,
                        deadlineNanos
                )) {
                    log.warn(
                            "AI retry skipped by total deadline: "
                                    + "provider={}, model={}, operationId={}, "
                                    + "attemptId={}, attempt={}, maxAttempts={}, "
                                    + "errorType={}, retryAfter={}",
                            provider,
                            model,
                            operationId,
                            context.attemptId(),
                            attempt,
                            maxAttempts,
                            exception.getErrorType(),
                            exception.getRetryAfter()
                    );
                    throw exception;
                }

                log.warn(
                        "Retrying AI provider operation: provider={}, "
                                + "model={}, operationId={}, attemptId={}, "
                                + "attempt={}, maxAttempts={}, errorType={}, "
                                + "providerRequestId={}, retryAfter={}, delayMs={}",
                        provider,
                        model,
                        operationId,
                        context.attemptId(),
                        attempt,
                        maxAttempts,
                        exception.getErrorType(),
                        exception.getProviderRequestId(),
                        exception.getRetryAfter(),
                        delay.toMillis()
                );

                sleep(delay, provider, model, exception);
                backoff = nextBackoff(backoff);
            }
        }

        throw new IllegalStateException(
                "AI retry loop completed unexpectedly"
        );
    }

    /**
     * Совместимость со старыми тестами/вызовами.
     */
    public AiChatResponse execute(
            String provider,
            String model,
            Supplier<AiChatResponse> action
    ) {
        return execute(
                provider,
                model,
                UUID.randomUUID(),
                Duration.ZERO,
                ignored -> action.get()
        );
    }

    private boolean shouldRetry(
            AiProviderException exception,
            int attempt,
            int maxAttempts
    ) {
        if (!properties.isEnabled()
                || attempt >= maxAttempts
                || !exception.isRetryable()) {
            return false;
        }

        Duration retryAfter = exception.getRetryAfter();

        return retryAfter == null
                || retryAfter.compareTo(
                properties.effectiveMaxRetryAfter()
        ) <= 0;
    }

    private Duration retryDelay(
            AiProviderException exception,
            Duration backoff
    ) {
        if (exception.getRetryAfter() != null) {
            return exception.getRetryAfter();
        }

        return withJitter(backoff);
    }

    private boolean fitsDeadline(
            Duration delay,
            Duration attemptTimeout,
            long deadlineNanos
    ) {
        long now = System.nanoTime();
        long required = safeAdd(
                delay.toNanos(),
                attemptTimeout.toNanos()
        );

        return safeAdd(now, required) <= deadlineNanos;
    }

    private Duration normalizeAttemptTimeout(
            Duration attemptTimeout
    ) {
        if (attemptTimeout == null) {
            return Duration.ZERO;
        }

        if (attemptTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "attemptTimeout не может быть отрицательным"
            );
        }

        return attemptTimeout;
    }

    private Duration nextBackoff(Duration current) {
        Duration max = properties.effectiveMaxBackoff();
        Duration next;

        try {
            next = current.multipliedBy(2);
        } catch (ArithmeticException exception) {
            return max;
        }

        return next.compareTo(max) > 0 ? max : next;
    }

    private void sleep(
            Duration duration,
            String provider,
            String model,
            AiProviderException cause
    ) {
        if (duration.isZero()) {
            return;
        }

        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new AiProviderException(
                    provider,
                    model,
                    cause.getStatusCode(),
                    cause.getProviderRequestId(),
                    cause.getProviderErrorCode(),
                    cause.getErrorType(),
                    false,
                    cause.isOutcomeAmbiguous(),
                    null,
                    "AI provider retry interrupted",
                    exception
            );
        }
    }

    private Duration withJitter(Duration duration) {
        long millis = duration.toMillis();

        if (millis <= 1) {
            return duration;
        }

        long lower = Math.max(1L, millis / 2);
        long jittered = ThreadLocalRandom.current()
                .nextLong(lower, millis + 1);

        return Duration.ofMillis(jittered);
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

}
