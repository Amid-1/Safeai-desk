package ru.safeai.gateway.ai.exception;

public class AiProviderRateLimitedException extends AiProviderException {

    public AiProviderRateLimitedException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                statusCode,
                providerRequestId,
                true,
                message,
                cause
        );
    }
}