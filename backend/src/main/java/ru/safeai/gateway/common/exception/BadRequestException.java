package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends ApiException {

    public BadRequestException(String publicMessage) {
        super(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, publicMessage);
    }

    public BadRequestException(String publicMessage, Throwable cause) {
        super(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                publicMessage,
                cause
        );
    }
}
