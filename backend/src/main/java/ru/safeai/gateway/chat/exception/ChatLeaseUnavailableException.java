package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class ChatLeaseUnavailableException extends ChatApiException {
    public ChatLeaseUnavailableException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            String message,
            Throwable cause
    ) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CHAT_LEASE_UNAVAILABLE",
                message,
                chatId,
                turnId,
                clientRequestId,
                null,
                cause
        );
    }
}
