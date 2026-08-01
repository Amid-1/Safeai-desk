package ru.safeai.gateway.ai.exception;

public class AiProviderQuotaExceededException
        extends AiProviderException {

    public AiProviderQuotaExceededException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            String providerErrorCode,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                statusCode,
                providerRequestId,
                providerErrorCode,
                AiProviderErrorType.QUOTA_EXHAUSTED,
                false,
                false,
                null,
                message,
                cause
        );
    }
}
