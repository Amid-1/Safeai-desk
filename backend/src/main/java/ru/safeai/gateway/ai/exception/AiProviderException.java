package ru.safeai.gateway.ai.exception;

import lombok.Getter;
import ru.safeai.gateway.ai.provider.ProviderEvidenceText;

import java.time.Duration;
import java.util.Objects;

@Getter
public class AiProviderException extends RuntimeException {

    private static final int MAX_PROVIDER_CHARS = 32;
    private static final int MAX_MODEL_CHARS = 100;
    private static final int MAX_PROVIDER_REQUEST_ID_CHARS = 255;
    private static final int MAX_PROVIDER_ERROR_CODE_CHARS = 100;

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

        this.provider = ProviderEvidenceText.required(
                provider,
                MAX_PROVIDER_CHARS,
                "provider"
        );
        this.model = ProviderEvidenceText.required(
                model,
                MAX_MODEL_CHARS,
                "model"
        );
        this.statusCode = statusCode;
        this.providerRequestId = ProviderEvidenceText.optional(
                providerRequestId,
                MAX_PROVIDER_REQUEST_ID_CHARS
        );
        this.providerErrorCode = ProviderEvidenceText.optional(
                providerErrorCode,
                MAX_PROVIDER_ERROR_CODE_CHARS
        );
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
}
