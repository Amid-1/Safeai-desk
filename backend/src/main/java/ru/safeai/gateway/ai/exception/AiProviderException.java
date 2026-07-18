package ru.safeai.gateway.ai.exception;

import lombok.Getter;

import java.time.Duration;

@Getter
public class AiProviderException extends RuntimeException {

    private final String provider;
    private final String model;
    private final Integer statusCode;
    private final String providerRequestId;
    private final AiProviderErrorType errorType;
    private final boolean retryRecommended;
    private final boolean outcomeAmbiguous;
    private final Duration retryAfter;

    public AiProviderException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            AiProviderErrorType errorType,
            boolean retryRecommended,
            boolean outcomeAmbiguous,
            Duration retryAfter,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.providerRequestId = providerRequestId;
        this.errorType = errorType;
        this.retryRecommended = retryRecommended;
        this.outcomeAmbiguous = outcomeAmbiguous;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() {
        return retryRecommended && !outcomeAmbiguous;
    }
}
