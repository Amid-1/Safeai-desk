package ru.safeai.gateway.chat.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiExceptionResponseSupport;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice
public class ChatInfrastructureExceptionHandler {

    private static final String CHAT_LOCK_UNAVAILABLE_MESSAGE =
            "Сервис блокировки чата временно недоступен";

    private final ApiExceptionResponseSupport responses;

    public ChatInfrastructureExceptionHandler(
            ApiErrorResponseFactory errorResponseFactory
    ) {
        this.responses = new ApiExceptionResponseSupport(
                errorResponseFactory
        );
    }

    @ExceptionHandler(ChatBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleChatBusy(
            ChatBusyException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.CONFLICT,
                ApiErrorCode.CHAT_BUSY,
                responses.safeMessage(
                        exception.getMessage(),
                        "В этот чат уже отправляется сообщение"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ChatLockUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleChatLockUnavailable(
            ChatLockUnavailableException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Chat lock service unavailable: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CHAT_LOCK_UNAVAILABLE,
                CHAT_LOCK_UNAVAILABLE_MESSAGE,
                request,
                null,
                null
        );
    }
}
