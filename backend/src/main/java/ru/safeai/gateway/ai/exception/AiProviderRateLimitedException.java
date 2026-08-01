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
        this(
                provider,
                model,
                statusCode,
                providerRequestId,
                null,
                retryAfter,
                retryRecommended,
                message,
                cause
        );
    }

    public AiProviderRateLimitedException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            String providerErrorCode,
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
                providerErrorCode,
                AiProviderErrorType.RATE_LIMITED,
                retryRecommended,
                false,
                retryAfter,
                message,
                cause
        );
    }
}
