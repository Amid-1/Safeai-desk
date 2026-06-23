package ru.safeai.gateway.ai;

public class AiProviderRateLimitedException extends AiProviderException {

    public AiProviderRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}