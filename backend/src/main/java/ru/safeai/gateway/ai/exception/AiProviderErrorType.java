package ru.safeai.gateway.ai.exception;

public enum AiProviderErrorType {
    CONNECT_FAILURE,
    TIMEOUT,
    RATE_LIMITED,
    OVERLOADED,
    AUTHENTICATION,
    INVALID_REQUEST,
    SERVER_ERROR,
    UNKNOWN
}
