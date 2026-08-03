package ru.safeai.gateway.chat.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class AiOutcomeAmbiguousException extends ChatApiException {
    public AiOutcomeAmbiguousException(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            Throwable cause
    ) {
        super(
                HttpStatus.CONFLICT,
                "AI_OUTCOME_AMBIGUOUS",
                "Результат AI-запроса неоднозначен; автоматический повтор запрещён",
                chatId,
                turnId,
                clientRequestId,
                null,
                cause
        );
    }
}
