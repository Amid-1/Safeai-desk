package ru.safeai.gateway.ai.provider;

import org.springframework.web.client.ResourceAccessException;

import ru.safeai.gateway.ai.exception.AiProviderErrorType;

import ru.safeai.gateway.ai.exception.AiProviderException;

import ru.safeai.gateway.ai.exception.AiProviderResponseTooLargeException;

import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;

import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;

public final class AiProviderExceptionFactory {

private AiProviderExceptionFactory() {
}

public static AiProviderException fromResourceAccess(
        String provider,
        String providerDisplayName,
        String model,
        ResourceAccessException exception
) {
    if (AiProviderSupport.isResponseTooLarge(exception)) {
        return new AiProviderResponseTooLargeException(
                provider,
                model,
                providerDisplayName
                        + " response exceeded configured body limit",
                exception
        );
    }

    if (AiProviderSupport.isConnectFailure(exception)) {
        return new AiProviderUnavailableException(
                provider,
                model,
                true,
                false,
                providerDisplayName + " connect failure",
                exception
        );
    }

    if (AiProviderSupport.isReadTimeout(exception)) {
        return new AiProviderTimeoutException(
                provider,
                model,
                true,
                providerDisplayName
                        + " read timeout after request submission",
                exception
        );
    }

    return new AiProviderUnavailableException(
            provider,
            model,
            false,
            true,
            providerDisplayName
                    + " network failure with ambiguous outcome",
            exception
    );
}

public static AiProviderException unknownFailure(
        String provider,
        String providerDisplayName,
        String model,
        RuntimeException exception
) {
    return new AiProviderException(
            provider,
            model,
            null,
            null,
            AiProviderErrorType.UNKNOWN,
            false,
            true,
            null,
            providerDisplayName
                    + " provider request failed after submission",
            exception
    );
}

public static AiProviderException parsingFailure(
        String provider,
        String model,
        String message
) {
    return new AiProviderException(
            provider,
            model,
            null,
            null,
            AiProviderErrorType.PROTOCOL_ERROR,
            false,
            true,
            null,
            message,
            null
    );
}

}
