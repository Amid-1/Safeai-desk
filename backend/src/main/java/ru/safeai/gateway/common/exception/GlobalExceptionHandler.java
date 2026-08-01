package ru.safeai.gateway.common.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Единственный common-level MVC exception advice.
 *
 * <p>Специализированные advice функциональных модулей должны иметь явно
 * более высокий приоритет. Отдельные common advice для chat availability
 * и optimistic locking использовать не нужно.</p>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler implements Ordered {

    private static final String VALIDATION_MESSAGE =
            "Ошибка валидации запроса";

    private static final String INTERNAL_ERROR_MESSAGE =
            "Внутренняя ошибка сервера";

    private static final String OPTIMISTIC_LOCK_MESSAGE =
            "Данные были изменены другим пользователем. "
                    + "Обновите данные и повторите операцию";

    private static final String CHAT_LOCK_UNAVAILABLE_MESSAGE =
            "Сервис блокировки чата временно недоступен";

    private static final String RATE_LIMIT_UNAVAILABLE_MESSAGE =
            "Сервис ограничения запросов временно недоступен";

    private static final String INVALID_REFRESH_TOKEN_MESSAGE =
            "Недействительный refresh token";

    /*
     * Во время Redis outage один request не должен создавать один полный
     * ERROR stack trace. Один экземпляр приложения пишет первый stack trace,
     * затем не чаще одного раза в минуту сообщает число подавленных записей.
     */
    private static final long RATE_LIMIT_LOG_INTERVAL_NANOS =
            TimeUnit.MINUTES.toNanos(1);

    private final ApiErrorResponseFactory
            errorResponseFactory;

    private final AtomicLong nextRateLimitLogNanos =
            new AtomicLong();

    private final LongAdder suppressedRateLimitLogs =
            new LongAdder();

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @ExceptionHandler(ChatBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleChatBusy(
            ChatBusyException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.CONFLICT,
                ApiErrorCode.CHAT_BUSY,
                safeMessage(
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
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CHAT_LOCK_UNAVAILABLE,
                CHAT_LOCK_UNAVAILABLE_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitUnavailable(
            RateLimitUnavailableException exception,
            HttpServletRequest request
    ) {
        logRateLimitUnavailable(
                exception,
                request
        );

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.RATE_LIMIT_UNAVAILABLE,
                RATE_LIMIT_UNAVAILABLE_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AuthServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthServiceUnavailable(
            AuthServiceUnavailableException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Authentication service unavailable: "
                        + "requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                "Сервис авторизации временно недоступен",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            BadRequestException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                safeMessage(
                        exception.getMessage(),
                        "Некорректный запрос"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                safeMessage(
                        exception.getMessage(),
                        "Ресурс не найден"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                safeMessage(
                        exception.getMessage(),
                        "Конфликт данных"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleForbiddenOperation(
            ForbiddenOperationException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.FORBIDDEN,
                safeMessage(
                        exception.getMessage(),
                        "Операция запрещена"
                ),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Access denied: requestId={}, path={}, type={}",
                requestId(request),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );

        return build(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.FORBIDDEN,
                "Доступ запрещён",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(ExpiredRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleExpiredRefreshToken(
            ExpiredRefreshTokenException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Expired refresh token: requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.EXPIRED_REFRESH_TOKEN,
                "Refresh token истёк",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(RefreshTokenReuseDetectedException.class)
    public ResponseEntity<ApiErrorResponse>
    handleRefreshTokenReuseDetected(
            RefreshTokenReuseDetectedException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Refresh token reuse detected: requestId={}, "
                        + "userId={}, organizationId={}, "
                        + "tokenFamilyId={}, path={}",
                requestId(request),
                exception.getUserId(),
                exception.getOrganizationId(),
                exception.getTokenFamilyId(),
                request.getRequestURI()
        );

        return build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                INVALID_REFRESH_TOKEN_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRefreshToken(
            InvalidRefreshTokenException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Invalid refresh token: requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                INVALID_REFRESH_TOKEN_MESSAGE,
                request,
                null,
                null
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();

        if (exception.getRetryAfterSeconds() > 0L) {
            headers.set(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(
                            exception.getRetryAfterSeconds()
                    )
            );
        }

        return build(
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                safeMessage(
                        exception.getPublicMessage(),
                        "Превышен лимит запросов"
                ),
                request,
                null,
                headers
        );
    }

    /**
     * Общий fallback только для прочих ApiException-наследников.
     * Специальные исключения выше имеют более конкретные mappings.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException exception,
            HttpServletRequest request
    ) {
        return build(
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
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            Exception exception,
            HttpServletRequest request
    ) {
        log.info(
                "Optimistic lock conflict: requestId={}, path={}, type={}",
                requestId(request),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );

        return build(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                OPTIMISTIC_LOCK_MESSAGE,
                request,
                null,
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
                bindingErrors(
                        exception.getBindingResult()
                )
        );
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindException(
            BindException exception,
            HttpServletRequest request
    ) {
        return validationResponse(
                request,
                bindingErrors(
                        exception.getBindingResult()
                )
        );
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        if (exception.isForReturnValue()) {
            log.error(
                    "Controller return value validation failed: "
                            + "requestId={}, path={}",
                    requestId(request),
                    request.getRequestURI(),
                    exception
            );

            return internalServerError(request);
        }

        Map<String, List<String>> fieldErrors =
                new LinkedHashMap<>();

        for (ParameterValidationResult result
                : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError error
                        : parameterErrors.getFieldErrors()) {
                    addError(
                            fieldErrors,
                            error.getField(),
                            defaultMessage(error)
                    );
                }

                for (ObjectError error
                        : parameterErrors.getGlobalErrors()) {
                    addError(
                            fieldErrors,
                            parameterName(result),
                            defaultMessage(error)
                    );
                }

                continue;
            }

            String field = parameterName(result);

            for (MessageSourceResolvable error
                    : result.getResolvableErrors()) {
                addError(
                        fieldErrors,
                        field,
                        defaultMessage(error)
                );
            }
        }

        for (MessageSourceResolvable error
                : exception.getCrossParameterValidationResults()) {
            addError(
                    fieldErrors,
                    "_global",
                    defaultMessage(error)
            );
        }

        return validationResponse(
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        Map<String, List<String>> fieldErrors =
                new LinkedHashMap<>();

        for (ConstraintViolation<?> violation
                : exception.getConstraintViolations()) {
            addError(
                    fieldErrors,
                    normalizeConstraintPath(
                            violation.getPropertyPath()
                                    .toString()
                    ),
                    violation.getMessage()
            );
        }

        return validationResponse(
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpServletRequest request
    ) {
        return build(
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
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return build(
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
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return build(
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
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return build(
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
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.METHOD_NOT_ALLOWED,
                ApiErrorCode.METHOD_NOT_ALLOWED,
                "HTTP-метод не поддерживается для этого ресурса",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Тип содержимого запроса не поддерживается",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.NOT_ACCEPTABLE,
                ApiErrorCode.NOT_ACCEPTABLE,
                "Запрошенный формат ответа не поддерживается",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                "Ресурс не найден",
                request,
                null,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse>
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
                    requestId(request),
                    request.getRequestURI(),
                    status.value(),
                    exception
            );
        }

        String message = status.is5xxServerError()
                ? INTERNAL_ERROR_MESSAGE
                : safeMessage(
                exception.getReason(),
                messageForStatus(status)
        );

        return build(
                status,
                codeForStatus(status),
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
    public ResponseEntity<ApiErrorResponse>
    handleExpectedAuthenticationFailure(
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "Неверные учётные данные",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    public ResponseEntity<ApiErrorResponse>
    handleAuthenticationInfrastructure(
            AuthenticationServiceException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Authentication infrastructure failure: "
                        + "requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                "Сервис авторизации временно недоступен",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse>
    handleUnexpectedAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unexpected authentication failure: "
                        + "requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return internalServerError(request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse>
    handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unexpected data integrity violation: "
                        + "requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return internalServerError(request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled exception: requestId={}, path={}",
                requestId(request),
                request.getRequestURI(),
                exception
        );

        return internalServerError(request);
    }

    private void logRateLimitUnavailable(
            RateLimitUnavailableException exception,
            HttpServletRequest request
    ) {
        long now = System.nanoTime();

        while (true) {
            long next =
                    nextRateLimitLogNanos.get();

            /*
             * Сравнение через разность корректно переживает переполнение
             * монотонного счётчика System.nanoTime().
             */
            if (now - next < 0L) {
                suppressedRateLimitLogs.increment();
                return;
            }

            long candidate =
                    now + RATE_LIMIT_LOG_INTERVAL_NANOS;

            if (!nextRateLimitLogNanos.compareAndSet(
                    next,
                    candidate
            )) {
                /*
                 * Другой поток мог обновить deadline.
                 * Обновляем now перед повторной проверкой.
                 */
                now = System.nanoTime();
                continue;
            }

            long suppressed =
                    suppressedRateLimitLogs.sumThenReset();

            log.error(
                    "Rate limit service unavailable: "
                            + "requestId={}, path={}, "
                            + "suppressedSinceLastLog={}",
                    requestId(request),
                    request.getRequestURI(),
                    suppressed,
                    exception
            );

            return;
        }
    }


    private ResponseEntity<ApiErrorResponse> validationResponse(
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors
    ) {
        Map<String, List<String>> normalized =
                fieldErrors == null
                        || fieldErrors.isEmpty()
                        ? Map.of(
                        "request",
                        List.of(
                                "Некорректное значение"
                        )
                )
                        : fieldErrors;

        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE,
                request,
                normalized,
                null
        );
    }

    private ResponseEntity<ApiErrorResponse> internalServerError(
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_MESSAGE,
                request,
                null,
                null
        );
    }

    private ResponseEntity<ApiErrorResponse> build(
            HttpStatusCode status,
            ApiErrorCode error,
            String message,
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors,
            HttpHeaders headers
    ) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(status);

        if (headers != null && !headers.isEmpty()) {
            builder.headers(headers);
        }

        builder.cacheControl(
                CacheControl.noStore()
        );

        return builder.body(
                errorResponseFactory.create(
                        status,
                        error,
                        message,
                        request,
                        fieldErrors
                )
        );
    }

    private Map<String, List<String>> bindingErrors(
            BindingResult bindingResult
    ) {
        Map<String, List<String>> errors =
                new LinkedHashMap<>();

        for (FieldError error
                : bindingResult.getFieldErrors()) {
            addError(
                    errors,
                    error.getField(),
                    defaultMessage(error)
            );
        }

        for (ObjectError error
                : bindingResult.getGlobalErrors()) {
            addError(
                    errors,
                    "_global",
                    defaultMessage(error)
            );
        }

        return errors;
    }

    private void addError(
            Map<String, List<String>> errors,
            String field,
            String message
    ) {
        String normalizedField =
                field == null || field.isBlank()
                        ? "_global"
                        : field.trim();

        String normalizedMessage =
                message == null || message.isBlank()
                        ? "Некорректное значение"
                        : message.trim();

        List<String> messages =
                errors.computeIfAbsent(
                        normalizedField,
                        ignored -> new ArrayList<>()
                );

        if (!messages.contains(normalizedMessage)) {
            messages.add(normalizedMessage);
        }
    }

    private String parameterName(
            ParameterValidationResult result
    ) {
        String parameterName =
                result.getMethodParameter()
                        .getParameterName();

        String base =
                parameterName == null
                        || parameterName.isBlank()
                        ? "parameter"
                        : parameterName;

        if (result.getContainerIndex() != null) {
            return base
                    + "["
                    + result.getContainerIndex()
                    + "]";
        }

        if (result.getContainerKey() != null) {
            return base
                    + "["
                    + result.getContainerKey()
                    + "]";
        }

        return base;
    }

    private String normalizeConstraintPath(
            String rawPath
    ) {
        if (rawPath == null || rawPath.isBlank()) {
            return "_global";
        }

        String normalized = rawPath.trim();
        int firstDot = normalized.indexOf('.');

        return firstDot < 0
                ? normalized
                : normalized.substring(
                firstDot + 1
        );
    }

    private String defaultMessage(
            MessageSourceResolvable error
    ) {
        String message = error.getDefaultMessage();

        return message == null || message.isBlank()
                ? "Некорректное значение"
                : message;
    }

    private ApiErrorCode codeForStatus(
            HttpStatusCode status
    ) {
        return switch (status.value()) {
            case 400 -> ApiErrorCode.BAD_REQUEST;
            case 401 -> ApiErrorCode.UNAUTHORIZED;
            case 403 -> ApiErrorCode.FORBIDDEN;
            case 404 -> ApiErrorCode.NOT_FOUND;
            case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ApiErrorCode.NOT_ACCEPTABLE;
            case 409 -> ApiErrorCode.CONFLICT;
            case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ApiErrorCode.RATE_LIMIT_EXCEEDED;
            default -> status.is5xxServerError()
                    ? ApiErrorCode.INTERNAL_SERVER_ERROR
                    : ApiErrorCode.BAD_REQUEST;
        };
    }

    private String messageForStatus(
            HttpStatusCode status
    ) {
        return switch (status.value()) {
            case 400 -> "Некорректный запрос";
            case 401 -> "Требуется авторизация";
            case 403 -> "Доступ запрещён";
            case 404 -> "Ресурс не найден";
            case 405 -> "HTTP-метод не поддерживается для этого ресурса";
            case 406 -> "Запрошенный формат ответа не поддерживается";
            case 409 -> "Конфликт данных";
            case 415 -> "Тип содержимого запроса не поддерживается";
            case 429 -> "Превышен лимит запросов";
            default -> status.is5xxServerError()
                    ? INTERNAL_ERROR_MESSAGE
                    : "Ошибка запроса";
        };
    }

    private String safeMessage(
            String message,
            String fallback
    ) {
        return message == null || message.isBlank()
                ? fallback
                : message.trim();
    }

    private String requestId(
            HttpServletRequest request
    ) {
        Object value = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        return value instanceof String requestId
                && !requestId.isBlank()
                ? requestId
                : "missing";
    }
}
