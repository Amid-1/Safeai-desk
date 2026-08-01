package ru.safeai.gateway.ai.exception;

import lombok.Getter;

import java.time.Duration;
import java.util.Objects;

@Getter
public class AiProviderException extends RuntimeException {

    private final String provider;
    private final String model;
    private final Integer statusCode;
    private final String providerRequestId;
    private final String providerErrorCode;
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
        this(
                provider,
                model,
                statusCode,
                providerRequestId,
                null,
                errorType,
                retryRecommended,
                outcomeAmbiguous,
                retryAfter,
                message,
                cause
        );
    }

    public AiProviderException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            String providerErrorCode,
            AiProviderErrorType errorType,
            boolean retryRecommended,
            boolean outcomeAmbiguous,
            Duration retryAfter,
            String message,
            Throwable cause
    ) {
        super(message, cause);

        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException(
                    "provider не должен быть пустым"
            );
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model не должен быть пустым"
            );
        }
        if (statusCode != null
                && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException(
                    "statusCode должен быть HTTP status"
            );
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "retryAfter не может быть отрицательным"
            );
        }

        this.provider = provider.trim();
        this.model = model.trim();
        this.statusCode = statusCode;
        this.providerRequestId = normalize(providerRequestId, 255);
        this.providerErrorCode = normalize(providerErrorCode, 100);
        this.errorType = Objects.requireNonNull(
                errorType,
                "errorType не должен быть null"
        );
        this.retryRecommended = retryRecommended;
        this.outcomeAmbiguous = outcomeAmbiguous;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() {
        return retryRecommended && !outcomeAmbiguous;
    }

    private static String normalize(
            String value,
            int maxLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }
}
