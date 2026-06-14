package ru.safeai.gateway.ai;

public class AiProviderTimeoutException extends AiProviderException {

    public AiProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}