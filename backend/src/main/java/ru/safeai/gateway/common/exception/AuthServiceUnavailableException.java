package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatus;

public final class AuthServiceUnavailableException
        extends ApiException {

    private static final String PUBLIC_MESSAGE =
            "Сервис авторизации временно недоступен";

    public AuthServiceUnavailableException(
            String internalMessage,
            Throwable cause
    ) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                PUBLIC_MESSAGE,
                internalMessage,
                cause
        );
    }
}