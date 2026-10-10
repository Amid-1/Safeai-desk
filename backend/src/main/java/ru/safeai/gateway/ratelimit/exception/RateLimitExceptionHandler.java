package ru.safeai.gateway.ratelimit.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiExceptionResponseSupport;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
@RestControllerAdvice
public class RateLimitExceptionHandler {

    private static final String RATE_LIMIT_UNAVAILABLE_MESSAGE =
            "Сервис ограничения запросов временно недоступен";

    private final ApiExceptionResponseSupport responses;
    private final RateLimitUnavailableLogger rateLimitLogger =
            new RateLimitUnavailableLogger();

    public RateLimitExceptionHandler(ApiErrorResponseFactory errorResponseFactory) {
        this.responses = new ApiExceptionResponseSupport(errorResponseFactory);
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleRateLimitUnavailable(
            RateLimitUnavailableException exception,
            HttpServletRequest request
    ) {
        rateLimitLogger.log(
                log,
                exception,
                request,
                responses.requestId(request)
        );

        return responses.build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.RATE_LIMIT_UNAVAILABLE,
                RATE_LIMIT_UNAVAILABLE_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        HttpHeaders headers =
                new HttpHeaders();

        if (exception.getRetryAfterSeconds() > 0L) {
            headers.set(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(
                            exception.getRetryAfterSeconds()
                    )
            );
        }

        return responses.build(
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                responses.safeMessage(
                        exception.getPublicMessage(),
                        "Превышен лимит запросов"
                ),
                request,
                null,
                headers
        );
    }

}
