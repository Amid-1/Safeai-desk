package ru.safeai.gateway.ai.exception;

public class AiProviderUnavailableException
        extends AiProviderException {

    public AiProviderUnavailableException(
            String provider,
            String model,
            boolean retryRecommended,
            boolean outcomeAmbiguous,
            String message,
            Throwable cause
    ) {
        super(
                provider,
                model,
                null,
                null,
                AiProviderErrorType.CONNECT_FAILURE,
                retryRecommended,
                outcomeAmbiguous,
                null,
                message,
                cause
        );
    }
}
