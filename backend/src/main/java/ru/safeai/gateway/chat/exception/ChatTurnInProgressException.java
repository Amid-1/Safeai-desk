package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.UUID;

public final class ChatTurnInProgressException
        extends ChatApiException {

    public ChatTurnInProgressException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            Duration retryAfter
    ) {
        super(
                HttpStatus.CONFLICT,
                "CHAT_TURN_IN_PROGRESS",
                "Запрос с таким clientRequestId ещё обрабатывается",
                chatId,
                turnId,
                clientRequestId,
                retryAfter,
                null
        );
    }
}