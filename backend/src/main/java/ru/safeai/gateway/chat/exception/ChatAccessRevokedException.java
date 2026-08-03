package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class ChatAccessRevokedException
        extends ChatApiException {

    public ChatAccessRevokedException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId
    ) {
        super(
                HttpStatus.FORBIDDEN,
                "CHAT_ACCESS_REVOKED_DURING_PROCESSING",
                "Доступ пользователя или организации был отозван во время обработки",
                chatId,
                turnId,
                clientRequestId,
                null,
                null
        );
    }
}