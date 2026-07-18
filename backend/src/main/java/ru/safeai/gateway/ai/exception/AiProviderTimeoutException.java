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
        super(
                provider,
                model,
                null,
                null,
                AiProviderErrorType.TIMEOUT,
                !outcomeAmbiguous,
                outcomeAmbiguous,
                null,
                message,
                cause
        );
    }
}
