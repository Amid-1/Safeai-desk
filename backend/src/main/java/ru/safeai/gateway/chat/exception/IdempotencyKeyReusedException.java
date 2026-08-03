package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class IdempotencyKeyReusedException extends ChatApiException {
    public IdempotencyKeyReusedException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId
    ) {
        super(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "clientRequestId уже использован для другого сообщения",
                chatId,
                turnId,
                clientRequestId,
                null,
                null
        );
    }
}
