package ru.safeai.gateway.ai.exception;

public class AiContextLimitException extends IllegalArgumentException {

    public AiContextLimitException(String message) {
        super(message);
    }
}
