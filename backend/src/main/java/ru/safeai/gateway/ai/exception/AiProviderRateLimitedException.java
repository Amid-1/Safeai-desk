package ru.safeai.gateway.ai.exception;

import java.time.Duration;

public class AiProviderRateLimitedException
        extends AiProviderException {

    public AiProviderRateLimitedException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            Duration retryAfter,
            boolean retryRecommended,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                statusCode,
                providerRequestId,
                AiProviderErrorType.RATE_LIMITED,
                retryRecommended,
                false,
                retryAfter,
                message,
                cause
        );
    }
}
