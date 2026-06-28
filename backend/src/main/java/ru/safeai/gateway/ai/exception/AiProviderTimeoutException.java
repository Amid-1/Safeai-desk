package ru.safeai.gateway.ai.exception;

public class AiProviderTimeoutException extends AiProviderException {

    public AiProviderTimeoutException(
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
                true,
                message,
                cause
        );
    }
}