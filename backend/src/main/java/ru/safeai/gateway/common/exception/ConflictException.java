package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String publicMessage) {
        this(ApiErrorCode.CONFLICT, publicMessage, null);
    }

    public ConflictException(String publicMessage, Throwable cause) {
        this(ApiErrorCode.CONFLICT, publicMessage, cause);
    }

    protected ConflictException(
            ApiErrorCode errorCode,
            String publicMessage,
            Throwable cause
    ) {
        super(HttpStatus.CONFLICT, errorCode, publicMessage, cause);
    }
}
