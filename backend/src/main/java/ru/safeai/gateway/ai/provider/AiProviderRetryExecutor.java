package ru.safeai.gateway.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptScope;
import ru.safeai.gateway.ai.execution.OutcomeCertainty;
import ru.safeai.gateway.ai.execution.ProviderFailureCertainty;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
public class AiProviderRetryExecutor {

    private final AiRetryProperties properties;
    private final AiExecutionAttemptScope attemptScope;

    @Autowired
    public AiProviderRetryExecutor(
            AiRetryProperties properties,
            AiExecutionAttemptScope attemptScope
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.attemptScope = Objects.requireNonNull(
                attemptScope,
                "attemptScope не должен быть null"
        );
    }

    /**
     * Compatibility constructor for tests. Physical execution is still
     * fail-closed: execute(...) requires a governed scope.
     */
    public AiProviderRetryExecutor(AiRetryProperties properties) {
        this(properties, new AiExecutionAttemptScope());
    }

    public AiChatResponse execute(
            String provider,
            String model,
            UUID operationId,
            Duration attemptTimeout,
            Function<AiProviderAttemptContext, AiChatResponse> action
    ) {
        Objects.requireNonNull(provider, "provider не должен быть null");
        Objects.requireNonNull(model, "model не должен быть null");
        Objects.requireNonNull(operationId, "operationId не должен быть null");
        Objects.requireNonNull(action, "action не должен быть null");

        /*
         * P1 invariant:
         * no provider attempt and no HTTP I/O may occur without the exact
         * governed execution scope created by AiExecutionService.
         */
        attemptScope.requireActive(operationId);

        Duration normalizedAttemptTimeout =
                normalizeAttemptTimeout(attemptTimeout);
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

        int maxAttempts = attemptScope.permittedMaxAttempts(
                properties.effectiveMaxAttempts()
        );

        Duration backoff = properties.effectiveInitialBackoff();
        long deadlineNanos = safeAdd(
                System.nanoTime(),
                saturatedNanos(totalTimeout)
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
                attemptScope.started(context);

                AiChatResponse response = action.apply(context);

                attemptScope.succeeded(context, response);
                return response;
            } catch (AiProviderException exception) {
                if (attemptScope.hasActiveAttempt(context)) {
                    attemptScope.failed(context, exception);
                }

                if (!shouldRetry(exception, attempt, maxAttempts)) {
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
                        saturatedMillis(delay)
                );

                sleep(delay, provider, model, exception);
                backoff = nextBackoff(backoff);
            } catch (RuntimeException exception) {
                if (attemptScope.hasActiveAttempt(context)) {
                    try {
                        attemptScope.ambiguous(context, exception);
                    } catch (RuntimeException recordingFailure) {
                        exception.addSuppressed(recordingFailure);
                    }
                }
                throw exception;
            }
        }

        throw new IllegalStateException(
                "AI retry loop completed unexpectedly"
        );
    }

    /**
     * Legacy source-compatible overload.
     *
     * <p>It intentionally does not create a synthetic execution scope. Calling
     * it outside AiExecutionService therefore fails before action.get() and
     * cannot bypass durable physical-attempt evidence.</p>
     */
    @Deprecated(forRemoval = true)
    public AiChatResponse execute(
            String provider,
            String model,
            Supplier<AiChatResponse> action
    ) {
        Objects.requireNonNull(action, "action не должен быть null");

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
                || !exception.isRetryable()
                || ProviderFailureCertainty.classify(exception)
                == OutcomeCertainty.AMBIGUOUS) {
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
        return exception.getRetryAfter() != null
                ? exception.getRetryAfter()
                : withJitter(backoff);
    }

    private boolean fitsDeadline(
            Duration delay,
            Duration attemptTimeout,
            long deadlineNanos
    ) {
        long now = System.nanoTime();
        long required = safeAdd(
                saturatedNanos(delay),
                saturatedNanos(attemptTimeout)
        );

        return safeAdd(now, required) <= deadlineNanos;
    }

    private static Duration normalizeAttemptTimeout(
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

        try {
            Duration next = current.multipliedBy(2);
            return next.compareTo(max) > 0 ? max : next;
        } catch (ArithmeticException exception) {
            return max;
        }
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
            Thread.sleep(saturatedMillis(duration));
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

    private static Duration withJitter(Duration duration) {
        long millis = saturatedMillis(duration);

        if (millis <= 1) {
            return Duration.ofMillis(millis);
        }

        long lower = Math.max(1L, millis / 2);
        long upperExclusive = millis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : millis + 1;

        if (lower >= upperExclusive) {
            return Duration.ofMillis(lower);
        }

        return Duration.ofMillis(
                ThreadLocalRandom.current()
                        .nextLong(lower, upperExclusive)
        );
    }

    private static long saturatedMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
