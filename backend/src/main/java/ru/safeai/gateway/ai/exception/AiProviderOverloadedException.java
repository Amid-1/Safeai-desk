package ru.safeai.gateway.ai.exception;

import java.time.Duration;

public class AiProviderOverloadedException
        extends AiProviderException {

    public AiProviderOverloadedException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            Duration retryAfter,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                statusCode,
                providerRequestId,
                AiProviderErrorType.OVERLOADED,
                true,
                false,
                retryAfter,
                message,
                cause
        );
    }
}
