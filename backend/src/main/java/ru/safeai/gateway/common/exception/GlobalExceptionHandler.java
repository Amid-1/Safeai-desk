package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String GLOBAL_ERROR_KEY = "_global";

    private final ApiErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                safeMessage(exception.getMessage(), "Ресурс не найден"),
                request,
                null
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                "CONFLICT",
                safeMessage(exception.getMessage(), "Конфликт данных"),
                request,
                null
        );
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleForbiddenOperation(
            ForbiddenOperationException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                safeMessage(exception.getMessage(), "Операция запрещена"),
                request,
                null
        );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            BadRequestException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                safeMessage(exception.getMessage(), "Некорректный запрос"),
                request,
                null
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS);

        if (exception.getRetryAfterSeconds() > 0) {
            builder.header(
                    HttpHeaders.RETRY_AFTER,
                    String.valueOf(exception.getRetryAfterSeconds())
            );
        }

        return builder.body(errorResponseFactory.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                safeMessage(exception.getMessage(), "Превышен лимит запросов"),
                request,
                null
        ));
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitUnavailable(
            RateLimitUnavailableException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Rate limit service unavailable for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RATE_LIMIT_UNAVAILABLE",
                "Сервис ограничения запросов временно недоступен",
                request,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        return validationResponse(
                request,
                bindingErrors(exception.getBindingResult())
        );
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        Map<String, List<String>> fieldErrors = new LinkedHashMap<>();

        exception.getParameterValidationResults().forEach(result -> {
            String parameterName = result.getMethodParameter().getParameterName();
            String key = parameterName == null ? "request" : parameterName;

            result.getResolvableErrors().forEach(error ->
                    fieldErrors.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(safeMessage(
                                    error.getDefaultMessage(),
                                    "Некорректное значение"
                            ))
            );
        });

        return validationResponse(request, fieldErrors);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindException(
            BindException exception,
            HttpServletRequest request
    ) {
        return validationResponse(
                request,
                bindingErrors(exception.getBindingResult())
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        Map<String, List<String>> fieldErrors = new LinkedHashMap<>();

        exception.getConstraintViolations().forEach(violation -> {
            String field = normalizeConstraintPath(violation.getPropertyPath());
            String message = safeMessage(
                    violation.getMessage(),
                    "Некорректное значение"
            );

            fieldErrors.computeIfAbsent(field, ignored -> new ArrayList<>())
                    .add(message);
        });

        return validationResponse(request, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Некорректный параметр запроса: "
                        + safeMessage(exception.getName(), "unknown"),
                request,
                null
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Не указан обязательный параметр запроса: "
                        + exception.getParameterName(),
                request,
                null
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Не указан обязательный заголовок запроса: "
                        + exception.getHeaderName(),
                request,
                null
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Invalid JSON body for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Некорректное тело запроса. Проверьте формат JSON и типы полей",
                request,
                null
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED);

        if (exception.getSupportedMethods() != null) {
            builder.header(
                    HttpHeaders.ALLOW,
                    String.join(", ", exception.getSupportedMethods())
            );
        }

        return builder.body(errorResponseFactory.create(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "HTTP-метод не поддерживается для этого endpoint",
                request,
                null
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Тип содержимого запроса не поддерживается",
                request,
                null
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotAcceptable(
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE",
                "Запрошенный формат ответа не поддерживается",
                request,
                null
        );
    }

    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "Ресурс не найден",
                request,
                null
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Authentication error for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Требуется авторизация",
                request,
                null
        );
    }

    @ExceptionHandler({
            AuthorizationDeniedException.class,
            AccessDeniedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            Exception exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Access denied for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Доступ запрещён",
                request,
                null
        );
    }

    @ExceptionHandler(ExpiredRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleExpiredRefreshToken(
            ExpiredRefreshTokenException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Expired refresh token for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "EXPIRED_REFRESH_TOKEN",
                "Refresh token истёк",
                request,
                null
        );
    }

    @ExceptionHandler(RefreshTokenReuseDetectedException.class)
    public ResponseEntity<ApiErrorResponse> handleRefreshTokenReuseDetected(
            RefreshTokenReuseDetectedException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Refresh token reuse detected: userId={}, organizationId={}, tokenFamilyId={}",
                exception.getUserId(),
                exception.getOrganizationId(),
                exception.getTokenFamilyId()
        );

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN",
                "Недействительный refresh token",
                request,
                null
        );
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRefreshToken(
            InvalidRefreshTokenException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Invalid refresh token for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN",
                "Недействительный refresh token",
                request,
                null
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Data integrity violation for request {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.CONFLICT,
                "CONFLICT",
                "Конфликт данных",
                request,
                null
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatus resolved = HttpStatus.resolve(
                exception.getStatusCode().value()
        );
        HttpStatus responseStatus = resolved == null
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : resolved;

        String message = responseStatus.is5xxServerError()
                ? "Внутренняя ошибка сервера"
                : safeMessage(
                exception.getReason(),
                responseStatus.getReasonPhrase()
        );

        if (responseStatus.is5xxServerError()) {
            log.error(
                    "ResponseStatusException for request {}",
                    request.getRequestURI(),
                    exception
            );
        } else {
            log.debug(
                    "ResponseStatusException for request {}",
                    request.getRequestURI(),
                    exception
            );
        }

        return buildResponse(
                responseStatus,
                stableResponseStatusCode(responseStatus),
                message,
                request,
                null
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Неожиданная ошибка при обработке запроса {}",
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "Внутренняя ошибка сервера",
                request,
                null
        );
    }

    private ResponseEntity<ApiErrorResponse> validationResponse(
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Ошибка валидации запроса",
                request,
                fieldErrors
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors
    ) {
        return ResponseEntity
                .status(status)
                .body(errorResponseFactory.create(
                        status,
                        error,
                        message,
                        request,
                        fieldErrors
                ));
    }

    private Map<String, List<String>> bindingErrors(BindingResult bindingResult) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            errors.computeIfAbsent(
                    fieldError.getField(),
                    ignored -> new ArrayList<>()
            ).add(safeMessage(
                    fieldError.getDefaultMessage(),
                    "Некорректное значение"
            ));
        }

        for (ObjectError globalError : bindingResult.getGlobalErrors()) {
            errors.computeIfAbsent(
                    GLOBAL_ERROR_KEY,
                    ignored -> new ArrayList<>()
            ).add(safeMessage(
                    globalError.getDefaultMessage(),
                    "Некорректное значение"
            ));
        }

        return errors;
    }

    private String normalizeConstraintPath(Path propertyPath) {
        if (propertyPath == null) {
            return "request";
        }

        String rawPath = propertyPath.toString();

        if (rawPath == null || rawPath.isBlank()) {
            return "request";
        }

        int lastDotIndex = rawPath.lastIndexOf('.');

        if (lastDotIndex >= 0 && lastDotIndex < rawPath.length() - 1) {
            return rawPath.substring(lastDotIndex + 1);
        }

        return rawPath;
    }

    private String stableResponseStatusCode(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST) {
            return "BAD_REQUEST";
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return "UNAUTHORIZED";
        }
        if (status == HttpStatus.FORBIDDEN) {
            return "FORBIDDEN";
        }
        if (status == HttpStatus.NOT_FOUND) {
            return "NOT_FOUND";
        }
        if (status == HttpStatus.CONFLICT) {
            return "CONFLICT";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return "RATE_LIMIT_EXCEEDED";
        }
        if (status.is4xxClientError()) {
            return "BAD_REQUEST";
        }
        return "INTERNAL_SERVER_ERROR";
    }

    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank()
                ? fallback
                : message;
    }
}
