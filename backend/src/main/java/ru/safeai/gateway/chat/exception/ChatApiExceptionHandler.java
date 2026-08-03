package ru.safeai.gateway.chat.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.chat.controller.ChatController;
import ru.safeai.gateway.chat.dto.ChatErrorResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        assignableTypes = ChatController.class
)
public class ChatApiExceptionHandler {

    private static final long MILLIS_PER_SECOND = 1_000L;

    private final Clock clock;

    public ChatApiExceptionHandler(
            Clock clock
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @ExceptionHandler(ChatApiException.class)
    public ResponseEntity<ChatErrorResponse> handle(
            ChatApiException exception
    ) {
        Objects.requireNonNull(
                exception,
                "exception не должен быть null"
        );

        Long retryAfterSeconds =
                resolveRetryAfterSeconds(exception);

        HttpHeaders headers = new HttpHeaders();

        if (retryAfterSeconds != null) {
            headers.set(
                    HttpHeaders.RETRY_AFTER,
                    retryAfterSeconds.toString()
            );
        }

        ChatErrorResponse body = new ChatErrorResponse(
                clock.instant(),
                exception.getStatus().value(),
                exception.getCode(),
                exception.getMessage(),
                exception.getChatId(),
                exception.getTurnId(),
                exception.getClientRequestId(),
                retryAfterSeconds
        );

        return ResponseEntity
                .status(exception.getStatus())
                .headers(headers)
                .body(body);
    }

    private static Long resolveRetryAfterSeconds(
            ChatApiException exception
    ) {
        Duration retryAfter = exception.getRetryAfter();

        if (retryAfter == null) {
            return null;
        }

        long retryAfterMillis = Math.max(
                0L,
                retryAfter.toMillis()
        );

        if (retryAfterMillis == 0L) {
            return 0L;
        }

        return Math.max(
                1L,
                Math.ceilDiv(
                        retryAfterMillis,
                        MILLIS_PER_SECOND
                )
        );
    }
}