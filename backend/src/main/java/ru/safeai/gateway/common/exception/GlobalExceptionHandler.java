package ru.safeai.gateway.common.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler implements Ordered {

    private static final String OPTIMISTIC_LOCK_MESSAGE =
            "Данные были изменены другим пользователем. "
                    + "Обновите данные и повторите операцию";

    private static final String CHAT_LOCK_UNAVAILABLE_MESSAGE =
            "Сервис блокировки чата временно недоступен";

    private static final String RATE_LIMIT_UNAVAILABLE_MESSAGE =
            "Сервис ограничения запросов временно недоступен";

    private static final String INVALID_REFRESH_TOKEN_MESSAGE =
            "Недействительный refresh token";

    private final ApiExceptionResponseSupport responses;
    private final ValidationErrorExtractor validationErrors =
            new ValidationErrorExtractor();
    private final RateLimitUnavailableLogger rateLimitLogger =
            new RateLimitUnavailableLogger();

    public GlobalExceptionHandler(
            ApiErrorResponseFactory errorResponseFactory
    ) {
        this.responses = new ApiExceptionResponseSupport(
                errorResponseFactory
        );
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @ExceptionHandler(ChatBusyException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleChatBusy(
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
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleChatLockUnavailable(
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

    @ExceptionHandler(BadRequestException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleBadRequest(
            BadRequestException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                responses.safeMessage(
                        exception.getMessage(),
                        "Некорректный запрос"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                responses.safeMessage(
                        exception.getMessage(),
                        "Ресурс не найден"
                ),
                request,
                null,
                null
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

    @ExceptionHandler(OrganizationVersionConflictException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleOrganizationVersionConflict(
            OrganizationVersionConflictException exception,
            HttpServletRequest request
    ) {
        log.info(
                "Organization optimistic version conflict: requestId={}, "
                        + "organizationId={}, expectedVersion={}, actualVersion={}, path={}",
                responses.requestId(request),
                exception.getOrganizationId(),
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

    @ExceptionHandler(ConflictException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleConflict(
            ConflictException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                exception.getStatus(),
                exception.getErrorCode(),
                responses.safeMessage(
                        exception.getPublicMessage(),
                        "Конфликт данных"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleForbiddenOperation(
            ForbiddenOperationException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                exception.getStatus(),
                exception.getErrorCode(),
                responses.safeMessage(
                        exception.getPublicMessage(),
                        "Операция запрещена"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Access denied: requestId={}, path={}, type={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );

        return responses.build(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.FORBIDDEN,
                "Доступ запрещён",
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

    @ExceptionHandler(ApiException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleApiException(
            ApiException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                exception.getStatus(),
                exception.getErrorCode(),
                exception.getPublicMessage(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler({
            OptimisticLockException.class,
            OptimisticLockingFailureException.class
    })
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleOptimisticLock(
            Exception exception,
            HttpServletRequest request
    ) {
        log.info(
                "Optimistic lock conflict: requestId={}, path={}, type={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );

        return responses.build(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                OPTIMISTIC_LOCK_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        return responses.validationResponse(
                request,
                validationErrors.bindingErrors(
                        exception.getBindingResult()
                )
        );
    }

    @ExceptionHandler(BindException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleBindException(
            BindException exception,
            HttpServletRequest request
    ) {
        return responses.validationResponse(
                request,
                validationErrors.bindingErrors(
                        exception.getBindingResult()
                )
        );
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        if (exception.isForReturnValue()) {
            log.error(
                    "Controller return value validation failed: "
                            + "requestId={}, path={}",
                    responses.requestId(request),
                    request.getRequestURI(),
                    exception
            );

            return responses.internalServerError(
                    request
            );
        }

        Map<String, List<String>> fieldErrors =
                new LinkedHashMap<>();

        for (ParameterValidationResult result
                : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError error
                        : parameterErrors.getFieldErrors()) {
                    validationErrors.addError(
                            fieldErrors,
                            error.getField(),
                            validationErrors.defaultMessage(error)
                    );
                }

                for (ObjectError error
                        : parameterErrors.getGlobalErrors()) {
                    validationErrors.addError(
                            fieldErrors,
                            validationErrors.parameterName(result),
                            validationErrors.defaultMessage(error)
                    );
                }

                continue;
            }

            String field =
                    validationErrors.parameterName(result);

            for (MessageSourceResolvable error
                    : result.getResolvableErrors()) {
                validationErrors.addError(
                        fieldErrors,
                        field,
                        validationErrors.defaultMessage(error)
                );
            }
        }

        for (MessageSourceResolvable error
                : exception.getCrossParameterValidationResults()) {
            validationErrors.addError(
                    fieldErrors,
                    "_global",
                    validationErrors.defaultMessage(error)
            );
        }

        return responses.validationResponse(
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        Map<String, List<String>> fieldErrors =
                new LinkedHashMap<>();

        for (ConstraintViolation<?> violation
                : exception.getConstraintViolations()) {
            validationErrors.addError(
                    fieldErrors,
                    validationErrors.normalizeConstraintPath(
                            violation.getPropertyPath()
                    ),
                    violation.getMessage()
            );
        }

        return responses.validationResponse(
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleUnreadableBody(
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Некорректное тело запроса. "
                        + "Проверьте формат JSON и типы полей",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Обязательный параметр отсутствует: "
                        + exception.getParameterName(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Обязательный заголовок отсутствует: "
                        + exception.getHeaderName(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Некорректное значение параметра: "
                        + exception.getName(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.METHOD_NOT_ALLOWED,
                ApiErrorCode.METHOD_NOT_ALLOWED,
                "HTTP-метод не поддерживается для этого ресурса",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Тип содержимого запроса не поддерживается",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.NOT_ACCEPTABLE,
                ApiErrorCode.NOT_ACCEPTABLE,
                "Запрошенный формат ответа не поддерживается",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                "Ресурс не найден",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatusCode status =
                exception.getStatusCode();

        if (status.is5xxServerError()) {
            log.error(
                    "ResponseStatusException with server status: "
                            + "requestId={}, path={}, status={}",
                    responses.requestId(request),
                    request.getRequestURI(),
                    status.value(),
                    exception
            );
        }

        String message =
                status.is5xxServerError()
                        ? ApiExceptionResponseSupport.INTERNAL_ERROR_MESSAGE
                        : responses.safeMessage(
                        exception.getReason(),
                        responses.messageForStatus(status)
                );

        return responses.build(
                status,
                responses.codeForStatus(status),
                message,
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler({
            BadCredentialsException.class,
            DisabledException.class,
            LockedException.class,
            AccountExpiredException.class,
            CredentialsExpiredException.class
    })
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleExpectedAuthenticationFailure(
            HttpServletRequest request
    ) {
        return responses.build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "Неверные учётные данные",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleAuthenticationInfrastructure(
            AuthenticationServiceException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Authentication infrastructure failure: "
                        + "requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                "Сервис авторизации временно недоступен",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleUnexpectedAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unexpected authentication failure: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.internalServerError(
                request
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unexpected data integrity violation: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.internalServerError(
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled exception: requestId={}, path={}",
                responses.requestId(request),
                request.getRequestURI(),
                exception
        );

        return responses.internalServerError(
                request
        );
    }

}
