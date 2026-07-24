package ru.safeai.gateway.ai.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;

import java.time.Duration;

@Slf4j
@RestControllerAdvice
@Order(0)
@RequiredArgsConstructor
public class AiExceptionHandler {

    private final ApiErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(AiProviderTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleTimeout(
            AiProviderTimeoutException exception,
            HttpServletRequest request
    ) {
        logProviderError(exception, request);

        return buildResponse(
                HttpStatus.GATEWAY_TIMEOUT,
                ApiErrorCode.AI_PROVIDER_TIMEOUT,
                "AI-провайдер не ответил вовремя",
                request,
                null
        );
    }

    @ExceptionHandler(AiProviderRateLimitedException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimited(
            AiProviderRateLimitedException exception,
            HttpServletRequest request
    ) {
        logProviderError(exception, request);

        return buildResponse(
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.AI_PROVIDER_RATE_LIMITED,
                "AI-провайдер временно ограничил количество запросов",
                request,
                exception.getRetryAfter()
        );
    }

    @ExceptionHandler(AiProviderOverloadedException.class)
    public ResponseEntity<ApiErrorResponse> handleOverloaded(
            AiProviderOverloadedException exception,
            HttpServletRequest request
    ) {
        logProviderError(exception, request);

        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AI_PROVIDER_OVERLOADED,
                "AI-провайдер временно перегружен",
                request,
                exception.getRetryAfter()
        );
    }

    @ExceptionHandler(AiProviderUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnavailable(
            AiProviderUnavailableException exception,
            HttpServletRequest request
    ) {
        logProviderError(exception, request);

        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AI_PROVIDER_UNAVAILABLE,
                "AI-провайдер временно недоступен",
                request,
                null
        );
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleProvider(
            AiProviderException exception,
            HttpServletRequest request
    ) {
        logProviderError(exception, request);

        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                ApiErrorCode.AI_PROVIDER_ERROR,
                "Ошибка при обращении к AI-провайдеру",
                request,
                null
        );
    }

    private void logProviderError(
            AiProviderException exception,
            HttpServletRequest request
    ) {
        String logMessage =
                "AI provider error: provider={}, model={}, "
                        + "errorType={}, statusCode={}, "
                        + "providerRequestId={}, retryRecommended={}, "
                        + "outcomeAmbiguous={}, path={}";

        if (exception.getErrorType()
                == AiProviderErrorType.AUTHENTICATION) {
            log.error(
                    logMessage,
                    exception.getProvider(),
                    exception.getModel(),
                    exception.getErrorType(),
                    exception.getStatusCode(),
                    exception.getProviderRequestId(),
                    exception.isRetryRecommended(),
                    exception.isOutcomeAmbiguous(),
                    request.getRequestURI(),
                    exception
            );

            return;
        }

        log.warn(
                logMessage,
                exception.getProvider(),
                exception.getModel(),
                exception.getErrorType(),
                exception.getStatusCode(),
                exception.getProviderRequestId(),
                exception.isRetryRecommended(),
                exception.isOutcomeAmbiguous(),
                request.getRequestURI(),
                exception
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            ApiErrorCode error,
            String message,
            HttpServletRequest request,
            Duration retryAfter
    ) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(status);

        if (retryAfter != null && !retryAfter.isNegative()) {
            long retryAfterSeconds = Math.max(
                    1L,
                    (long) Math.ceil(
                            retryAfter.toMillis() / 1000.0
                    )
            );

            builder.header(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(retryAfterSeconds)
            );
        }

        return builder.body(
                errorResponseFactory.create(
                        status,
                        error,
                        message,
                        request,
                        null
                )
        );
    }
}