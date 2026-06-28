package ru.safeai.gateway.ai.exception;

import lombok.Getter;

@Getter
public class AiProviderException extends RuntimeException {

    private final String provider;
    private final String model;
    private final Integer statusCode;
    private final String providerRequestId;
    private final boolean retryable;

    public AiProviderException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            boolean retryable,
            String message
    ) {
        this(provider, model, statusCode, providerRequestId, retryable, message, null);
    }

    public AiProviderException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            boolean retryable,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.providerRequestId = providerRequestId;
        this.retryable = retryable;
    }
}