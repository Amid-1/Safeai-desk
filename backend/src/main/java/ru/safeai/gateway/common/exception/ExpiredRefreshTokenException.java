package ru.safeai.gateway.common.exception;

public class ExpiredRefreshTokenException extends InvalidRefreshTokenException {

    public ExpiredRefreshTokenException(String message) {
        super(message);
    }
}