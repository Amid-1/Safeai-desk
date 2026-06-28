package ru.safeai.gateway.ai.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AiExceptionHandler {

    private final ApiErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(AiProviderTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderTimeout(
            AiProviderTimeoutException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider timeout: provider={}, model={}, requestId={}, retryable={}, message={}",
                exception.getProvider(),
                exception.getModel(),
                exception.getProviderRequestId(),
                exception.isRetryable(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)
                .body(errorResponseFactory.create(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "AI_PROVIDER_TIMEOUT",
                        "AI provider не ответил вовремя",
                        request,
                        null
                ));
    }

    @ExceptionHandler(AiProviderRateLimitedException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderRateLimited(
            AiProviderRateLimitedException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider rate limited: provider={}, model={}, statusCode={}, requestId={}, retryable={}, message={}",
                exception.getProvider(),
                exception.getModel(),
                exception.getStatusCode(),
                exception.getProviderRequestId(),
                exception.isRetryable(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorResponseFactory.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AI_PROVIDER_RATE_LIMITED",
                        "AI provider временно ограничил запросы",
                        request,
                        null
                ));
    }

    @ExceptionHandler(AiProviderUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderUnavailable(
            AiProviderUnavailableException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider unavailable: provider={}, model={}, requestId={}, retryable={}, message={}",
                exception.getProvider(),
                exception.getModel(),
                exception.getProviderRequestId(),
                exception.isRetryable(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorResponseFactory.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AI_PROVIDER_UNAVAILABLE",
                        "AI provider временно недоступен",
                        request,
                        null
                ));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderException(
            AiProviderException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider error: provider={}, model={}, statusCode={}, requestId={}, retryable={}, message={}",
                exception.getProvider(),
                exception.getModel(),
                exception.getStatusCode(),
                exception.getProviderRequestId(),
                exception.isRetryable(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(errorResponseFactory.create(
                        HttpStatus.BAD_GATEWAY,
                        "AI_PROVIDER_ERROR",
                        "Ошибка AI provider",
                        request,
                        null
                ));
    }
}