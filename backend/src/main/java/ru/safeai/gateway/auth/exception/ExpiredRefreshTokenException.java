package ru.safeai.gateway.auth.exception;

import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiException;

import org.springframework.http.HttpStatus;

public class ExpiredRefreshTokenException extends ApiException {

    public ExpiredRefreshTokenException(String internalMessage) {
        super(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.EXPIRED_REFRESH_TOKEN,
                "Refresh token истёк",
                new IllegalStateException(internalMessage)
        );
    }
}
