package ru.safeai.gateway.chat.exception;

public class ChatLockUnavailableException extends RuntimeException {

    public ChatLockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
