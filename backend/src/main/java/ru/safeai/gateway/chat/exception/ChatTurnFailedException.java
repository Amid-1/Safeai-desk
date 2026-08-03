package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class ChatTurnFailedException extends ChatApiException {

    public ChatTurnFailedException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            String failureCode,
            Throwable cause
    ) {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                resolveFailureCode(failureCode),
                "AI-запрос завершился ошибкой",
                chatId,
                turnId,
                clientRequestId,
                null,
                cause
        );
    }

    private static String resolveFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return "CHAT_TURN_FAILED";
        }

        return failureCode.trim();
    }
}