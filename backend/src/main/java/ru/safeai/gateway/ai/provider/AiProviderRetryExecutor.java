package ru.safeai.gateway.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptScope;
import ru.safeai.gateway.ai.execution.OutcomeCertainty;
import ru.safeai.gateway.ai.execution.ProviderFailureCertainty;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
public class AiProviderRetryExecutor {

    private final AiRetryProperties properties;
    private final AiExecutionAttemptScope attemptScope;
    private final Optional<AiProviderMetrics> metrics;

    @Autowired
    public AiProviderRetryExecutor(
            AiRetryProperties properties,
            AiExecutionAttemptScope attemptScope,
            Optional<AiProviderMetrics> metrics
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.attemptScope = Objects.requireNonNull(
                attemptScope,
                "attemptScope не должен быть null"
        );
        this.metrics = Objects.requireNonNull(metrics);
    }

    public AiProviderRetryExecutor(AiRetryProperties properties, AiExecutionAttemptScope attemptScope) {
        this(properties, attemptScope, Optional.empty());
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
        try {
            attemptScope.requireActive(operationId);
        } catch (RuntimeException failure) {
            metrics.ifPresent(m -> m.gateFailure(provider));
            throw failure;
        }

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
        long deadlineNanos = System.nanoTime() + totalTimeout.toNanos();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            requireDispatchTime(provider, model, deadlineNanos);
            AiProviderAttemptContext context =
                    new AiProviderAttemptContext(
                            operationId,
                            UUID.randomUUID(),
                            attempt,
                            maxAttempts
                    );

            boolean enteredAdapter = false;
            try {
                attemptScope.started(context);

                // Контекст и долговечный lease уже созданы; проверяем отзыв
                // непосредственно перед входом в адаптер физического I/O.
                requireDispatchTime(provider, model, deadlineNanos);
                attemptScope.beforeNetworkDispatch(context);
                requireDispatchTime(provider, model, deadlineNanos);
                enteredAdapter = true;
                Supplier<AiChatResponse> dispatch = () -> AiProviderDispatchDeadline.within(
                        deadlineNanos, () -> action.apply(context));
                AiChatResponse response = metrics.isPresent()
                        ? metrics.get().observe(provider, context.attemptNumber(), dispatch)
                        : dispatch.get();

                recordTerminal(provider, () -> attemptScope.succeeded(context, response));
                return response;
            } catch (AiProviderException exception) {
                if (!enteredAdapter) metrics.ifPresent(m -> m.gateFailure(provider));
                // Neither a rejected STARTED/dispatch gate nor post-success
                // governance rejection is a retryable provider response.
                if (!attemptScope.hasActiveAttempt(context)) throw exception;
                recordTerminal(provider, () -> attemptScope.failed(context, exception));

                if (!enteredAdapter || !shouldRetry(exception, attempt, maxAttempts)) {
                    throw exception;
                }

                Duration delay = retryDelay(exception, backoff);

                if (exceedsDeadline(
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
                // Scheduler delays and durable recording may have consumed the budget.
                if (exceedsDeadline(Duration.ZERO, normalizedAttemptTimeout, deadlineNanos)) {
                    throw exception;
                }
                backoff = nextBackoff(backoff);
            } catch (RuntimeException exception) {
                if (!enteredAdapter) metrics.ifPresent(m -> m.gateFailure(provider));
                if (attemptScope.hasActiveAttempt(context)) {
                    try {
                        recordTerminal(provider, () -> attemptScope.ambiguous(context, exception));
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

    private void recordTerminal(String provider, Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException failure) {
            metrics.ifPresent(m -> m.recordingFailure(provider));
            throw failure;
        }
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
                || exception.getErrorType() != AiProviderErrorType.RATE_LIMITED
                || !Integer.valueOf(429).equals(exception.getStatusCode())
                || ProviderFailureCertainty.classify(exception)
                != OutcomeCertainty.KNOWN_REJECTED) {
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

    private static boolean exceedsDeadline(
            Duration delay,
            Duration attemptTimeout,
            long deadlineNanos
    ) {
        long required = safeAdd(
                saturatedNanos(delay),
                saturatedNanos(attemptTimeout)
        );

        long remaining = AiProviderDispatchDeadline.remaining(deadlineNanos);
        return remaining <= 0 || required > remaining;
    }

    private static void requireDispatchTime(String provider, String model, long deadlineNanos) {
        if (Thread.currentThread().isInterrupted()) {
            throw new AiProviderTimeoutException(provider, model, true,
                    "AI provider dispatch interrupted", null);
        }
        if (AiProviderDispatchDeadline.remaining(deadlineNanos) <= 0) {
            throw new AiProviderTimeoutException(provider, model, true,
                    "AI provider total deadline exceeded", null);
        }
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
            Thread.sleep(duration);
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
