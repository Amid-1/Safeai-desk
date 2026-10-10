package ru.safeai.gateway.chat.exception;

import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ConflictException;

public class ChatBusyException extends ConflictException {

    public ChatBusyException(String publicMessage) {
        super(ApiErrorCode.CHAT_BUSY, publicMessage, null);
    }
}
