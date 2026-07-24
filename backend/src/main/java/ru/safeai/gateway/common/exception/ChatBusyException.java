package ru.safeai.gateway.common.exception;

public class ChatBusyException extends ConflictException {

    public ChatBusyException(String publicMessage) {
        super(ApiErrorCode.CHAT_BUSY, publicMessage, null);
    }
}
