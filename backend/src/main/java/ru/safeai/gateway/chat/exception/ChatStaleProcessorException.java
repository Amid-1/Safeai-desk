package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class ChatStaleProcessorException extends ChatApiException {
    public ChatStaleProcessorException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId
    ) {
        super(
                HttpStatus.CONFLICT,
                "CHAT_PROCESSOR_FENCED",
                "Процесс потерял право завершать chat turn",
                chatId,
                turnId,
                clientRequestId,
                null,
                null
        );
    }
}
