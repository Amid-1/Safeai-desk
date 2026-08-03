package ru.safeai.gateway.chat.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Getter
public abstract class ChatApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final UUID chatId;
    private final UUID turnId;
    private final UUID clientRequestId;
    private final Duration retryAfter;

    protected ChatApiException(
            HttpStatus status,
            String code,
            String message,
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            Duration retryAfter,
            Throwable cause
    ) {
        super(
                Objects.requireNonNull(
                        message,
                        "message не должен быть null"
                ),
                cause
        );

        this.status = Objects.requireNonNull(
                status,
                "status не должен быть null"
        );

        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "code не должен быть пустым"
            );
        }

        if (retryAfter != null
                && retryAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "retryAfter не может быть отрицательным"
            );
        }

        this.code = code.trim();
        this.chatId = chatId;
        this.turnId = turnId;
        this.clientRequestId = clientRequestId;
        this.retryAfter = retryAfter;
    }
}