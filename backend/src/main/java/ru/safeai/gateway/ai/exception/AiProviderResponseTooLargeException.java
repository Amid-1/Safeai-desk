package ru.safeai.gateway.ai.exception;

public class AiProviderResponseTooLargeException
        extends AiProviderException {

    public AiProviderResponseTooLargeException(
            String provider,
            String model,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                null,
                null,
                AiProviderErrorType.RESPONSE_TOO_LARGE,
                false,
                false,
                null,
                message,
                cause
        );
    }
}
