package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class ChatQuotaExceededException extends ChatApiException {
    public ChatQuotaExceededException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            String dimension
    ) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "AI_QUOTA_EXCEEDED",
                "AI quota исчерпана: " + dimension,
                chatId,
                turnId,
                clientRequestId,
                null,
                null
        );
    }
}
