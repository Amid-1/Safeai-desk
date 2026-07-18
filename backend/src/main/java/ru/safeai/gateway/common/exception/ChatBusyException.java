package ru.safeai.gateway.common.exception;

public class ChatBusyException extends ConflictException {
    public ChatBusyException(String message) {
        super(message);
    }
}
