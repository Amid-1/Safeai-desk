package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String publicMessage) {
        super(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, publicMessage);
    }
}
