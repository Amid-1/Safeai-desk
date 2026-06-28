package ru.safeai.gateway.ai.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;

import java.time.Duration;
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

                log.warn(
                        "Retrying AI provider request: provider={}, model={}, attempt={}, maxAttempts={}, statusCode={}, requestId={}, error={}",
                        provider,
                        model,
                        attempt,
                        maxAttempts,
                        exception.getStatusCode(),
                        exception.getProviderRequestId(),
                        exception.getMessage()
                );

                sleep(backoff, provider, model, exception);
                backoff = nextBackoff(backoff);
            }
        }

        throw new AiProviderException(
                provider,
                model,
                null,
                null,
                false,
                "AI provider retry failed unexpectedly"
        );
    }

    private boolean shouldRetry(
            AiProviderException exception,
            int attempt,
            int maxAttempts
    ) {
        return properties.isEnabled()
                && exception.isRetryable()
                && attempt < maxAttempts;
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
                    false,
                    "AI provider retry interrupted",
                    exception
            );
        }
    }
}