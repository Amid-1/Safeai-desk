package ru.safeai.gateway.common.exception;

public class ChatLockUnavailableException extends RuntimeException {
    public ChatLockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
