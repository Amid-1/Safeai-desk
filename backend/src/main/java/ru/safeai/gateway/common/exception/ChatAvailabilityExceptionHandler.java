package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@RequiredArgsConstructor
public class ChatAvailabilityExceptionHandler {
    private final ApiErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(ChatLockUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleChatLockUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                errorResponseFactory.create(HttpStatus.SERVICE_UNAVAILABLE,
                        "CHAT_LOCK_UNAVAILABLE",
                        "Сервис блокировки чата временно недоступен",
                        request, null));
    }

    @ExceptionHandler(ChatBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleChatBusy(
            ChatBusyException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                errorResponseFactory.create(HttpStatus.CONFLICT,
                        "CHAT_BUSY", exception.getMessage(), request, null));
    }
}
