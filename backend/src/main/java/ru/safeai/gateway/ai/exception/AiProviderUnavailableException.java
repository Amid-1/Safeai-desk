package ru.safeai.gateway.ai.exception;

public class AiProviderUnavailableException extends AiProviderException {

    public AiProviderUnavailableException(
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