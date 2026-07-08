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
                "AI provider timeout: provider={}, model={}, path={}",
                exception.getProvider(),
                exception.getModel(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.GATEWAY_TIMEOUT,
                "AI_PROVIDER_TIMEOUT",
                "AI-провайдер не ответил вовремя",
                request
        );
    }

    @ExceptionHandler(AiProviderRateLimitedException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderRateLimited(
            AiProviderRateLimitedException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider rate limited: provider={}, model={}, statusCode={}, providerRequestId={}, path={}",
                exception.getProvider(),
                exception.getModel(),
                exception.getStatusCode(),
                exception.getProviderRequestId(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.TOO_MANY_REQUESTS,
                "AI_PROVIDER_RATE_LIMITED",
                "AI-провайдер временно ограничил количество запросов",
                request
        );
    }

    @ExceptionHandler(AiProviderUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderUnavailable(
            AiProviderUnavailableException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider unavailable: provider={}, model={}, path={}",
                exception.getProvider(),
                exception.getModel(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_PROVIDER_UNAVAILABLE",
                "AI-провайдер временно недоступен",
                request
        );
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderException(
            AiProviderException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "AI provider error: provider={}, model={}, statusCode={}, providerRequestId={}, retryable={}, path={}",
                exception.getProvider(),
                exception.getModel(),
                exception.getStatusCode(),
                exception.getProviderRequestId(),
                exception.isRetryable(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                "AI_PROVIDER_ERROR",
                "Ошибка при обращении к AI-провайдеру",
                request
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(status)
                .body(errorResponseFactory.create(
                        status,
                        error,
                        message,
                        request,
                        null
                ));
    }
}