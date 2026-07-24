package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenOperationException extends ApiException {

    public ForbiddenOperationException(String publicMessage) {
        super(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, publicMessage);
    }
}
