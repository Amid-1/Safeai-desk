package ru.safeai.gateway.ai.exception;

public class AiProviderTimeoutException
        extends AiProviderException {

    public AiProviderTimeoutException(
            String provider,
            String model,
            boolean outcomeAmbiguous,
            String message,
            Throwable cause
    ) {
        this(
                provider,
                model,
                null,
                null,
                outcomeAmbiguous,
                message,
                cause
        );
    }

    public AiProviderTimeoutException(
            String provider,
            String model,
            Integer statusCode,
            String providerRequestId,
            boolean outcomeAmbiguous,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                statusCode,
                providerRequestId,
                AiProviderErrorType.TIMEOUT,
                !outcomeAmbiguous,
                outcomeAmbiguous,
                null,
                message,
                cause
        );
    }
}
