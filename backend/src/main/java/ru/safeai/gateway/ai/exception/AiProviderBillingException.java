package ru.safeai.gateway.ai.exception;

public class AiProviderBillingException
        extends AiProviderException {

    public AiProviderBillingException(
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
                AiProviderErrorType.BILLING_ERROR,
                false,
                false,
                null,
                message,
                cause
        );
    }
}
