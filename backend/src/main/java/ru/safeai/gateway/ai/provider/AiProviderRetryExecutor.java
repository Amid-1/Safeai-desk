package ru.safeai.gateway.ai.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiProviderRetryExecutor {

    private final AiRetryProperties properties;

    public AiChatResponse execute(
            String provider,
            String model,
            Supplier<AiChatResponse> action
    ) {
        int maxAttempts = properties.effectiveMaxAttempts();
        Duration backoff = properties.effectiveInitialBackoff();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (AiProviderException exception) {
                if (!shouldRetry(exception, attempt, maxAttempts)) {
                    throw exception;
                }

                Duration delay = retryDelay(exception, backoff);

                log.warn(
                        "Retrying safe AI provider operation: provider={}, model={}, attempt={}, maxAttempts={}, errorType={}, retryAfter={}, delayMs={}",
                        provider,
                        model,
                        attempt,
                        maxAttempts,
                        exception.getErrorType(),
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

    private Duration nextBackoff(Duration current) {
        Duration next = current.multipliedBy(2);
        Duration max = properties.effectiveMaxBackoff();

        return next.compareTo(max) > 0 ? max : next;
    }

    private void sleep(
            Duration duration,
            String provider,
            String model,
            AiProviderException cause
    ) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new AiProviderException(
                    provider,
                    model,
                    cause.getStatusCode(),
                    cause.getProviderRequestId(),
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

        long jitter = ThreadLocalRandom.current()
                .nextLong(0, Math.max(1, millis / 2));

        return Duration.ofMillis(millis + jitter);
    }
}
