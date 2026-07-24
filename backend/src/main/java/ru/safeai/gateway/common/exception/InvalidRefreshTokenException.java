package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException(String internalMessage) {
        super(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                "Недействительный refresh token",
                new IllegalStateException(internalMessage)
        );
    }

    public InvalidRefreshTokenException(String internalMessage, Throwable cause) {
        super(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                "Недействительный refresh token",
                new IllegalStateException(internalMessage, cause)
        );
    }
}
