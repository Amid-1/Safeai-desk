package ru.safeai.gateway.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiExceptionResponseSupport;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RestControllerAdvice
public class AuthExceptionHandler {

    private static final String INVALID_REFRESH_TOKEN_MESSAGE =
            "Недействительный refresh token";

    private final ApiExceptionResponseSupport responses;

    public AuthExceptionHandler(ApiErrorResponseFactory errorResponseFactory) {
        this.responses = new ApiExceptionResponseSupport(errorResponseFactory);
    }

    @ExceptionHandler(AuthServiceUnavailableException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleAuthServiceUnavailable(
            AuthServiceUnavailableException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Authentication service unavailable: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                exception.getPublicMessage(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ExpiredRefreshTokenException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleExpiredRefreshToken(
            ExpiredRefreshTokenException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Expired refresh token: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.EXPIRED_REFRESH_TOKEN,
                "Refresh token истёк",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleInvalidRefreshToken(
            InvalidRefreshTokenException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Invalid refresh token: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                INVALID_REFRESH_TOKEN_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(RefreshTokenReuseDetectedException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleRefreshTokenReuseDetected(
            RefreshTokenReuseDetectedException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Refresh token reuse detected: requestId={}, "
                        + "userId={}, organizationId={}, tokenFamilyId={}, path={}",
                responses.requestId(request),
                exception.getUserId(),
                exception.getOrganizationId(),
                exception.getTokenFamilyId(),
                request.getRequestURI()
        );

        return responses.build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                INVALID_REFRESH_TOKEN_MESSAGE,
                request,
                null,
                null
        );
    }

}
