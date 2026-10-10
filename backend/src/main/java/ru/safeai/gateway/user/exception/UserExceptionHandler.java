package ru.safeai.gateway.user.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiExceptionResponseSupport;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class UserExceptionHandler {

    private final ApiExceptionResponseSupport responses;

    public UserExceptionHandler(
            ApiErrorResponseFactory errorResponseFactory
    ) {
        this.responses = new ApiExceptionResponseSupport(
                errorResponseFactory
        );
    }

    @ExceptionHandler(UserVersionConflictException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleUserVersionConflict(
            UserVersionConflictException exception,
            HttpServletRequest request
    ) {
        log.info(
                "User optimistic version conflict: requestId={}, "
                        + "userId={}, expectedVersion={}, actualVersion={}, path={}",
                responses.requestId(request),
                exception.getUserId(),
                exception.getExpectedVersion(),
                exception.getActualVersion(),
                request.getRequestURI()
        );

        return responses.build(
                exception.getStatus(),
                exception.getErrorCode(),
                exception.getPublicMessage(),
                request,
                null,
                null
        );
    }
}
