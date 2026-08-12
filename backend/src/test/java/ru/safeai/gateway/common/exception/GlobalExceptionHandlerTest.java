package ru.safeai.gateway.common.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private static final Instant FIXED_TIME =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final String REQUEST_ID =
            "test-request-id";

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                FIXED_TIME,
                ZoneOffset.UTC
        );

        handler = new GlobalExceptionHandler(
                new ApiErrorResponseFactory(clock)
        );
    }

    @Test
    void handlerHasLowestPrecedence() {
        assertThat(handler.getOrder())
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void handleChatBusyReturns409AndStableCode() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleChatBusy(
                        new ChatBusyException(
                                "В этот чат уже отправляется сообщение"
                        ),
                        request("/api/chats/1/messages")
                );

        assertError(
                response,
                HttpStatus.CONFLICT,
                ApiErrorCode.CHAT_BUSY,
                "В этот чат уже отправляется сообщение",
                "/api/chats/1/messages"
        );
    }

    @Test
    void handleChatLockUnavailableReturns503AndDoesNotLeakCause() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleChatLockUnavailable(
                        new ChatLockUnavailableException(
                                "Redis lock failed",
                                new RuntimeException(
                                        "redis-password=secret"
                                )
                        ),
                        request("/api/chats/1/messages")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CHAT_LOCK_UNAVAILABLE,
                "Сервис блокировки чата временно недоступен",
                "/api/chats/1/messages"
        );

        assertThat(body.message())
                .doesNotContain(
                        "Redis",
                        "secret",
                        "password"
                );
    }

    @Test
    void handleRateLimitUnavailableReturnsSafe503() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleRateLimitUnavailable(
                        new RateLimitUnavailableException(
                                "Redis недоступен",
                                new RuntimeException(
                                        "redis.internal:6379"
                                )
                        ),
                        request("/api/chats")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.RATE_LIMIT_UNAVAILABLE,
                "Сервис ограничения запросов временно недоступен",
                "/api/chats"
        );

        assertThat(body.message())
                .doesNotContain(
                        "Redis",
                        "redis.internal"
                );
    }

    @Test
    void handleAuthServiceUnavailableReturnsSafe503() {
        AuthServiceUnavailableException exception =
                new AuthServiceUnavailableException(
                        "Authentication status lookup failed",
                        new RuntimeException(
                                "redis.internal:6379"
                        )
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleAuthServiceUnavailable(
                        exception,
                        request("/api/auth/login")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                "Сервис авторизации временно недоступен",
                "/api/auth/login"
        );

        assertThat(body.message())
                .doesNotContain(
                        "Authentication status lookup failed",
                        "redis.internal"
                );
    }

    @Test
    void handleBadRequestReturns400() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleBadRequest(
                        new BadRequestException(
                                "dateFrom должен быть раньше dateTo"
                        ),
                        request("/api/reports")
                );

        assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "dateFrom должен быть раньше dateTo",
                "/api/reports"
        );
    }

    @Test
    void handleResourceNotFoundReturns404() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleResourceNotFound(
                        new ResourceNotFoundException(
                                "Чат не найден"
                        ),
                        request("/api/chats/404")
                );

        assertError(
                response,
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                "Чат не найден",
                "/api/chats/404"
        );
    }

    @Test
    void handleConflictReturns409() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleConflict(
                        new ConflictException(
                                "Email уже используется"
                        ),
                        request("/api/users")
                );

        assertError(
                response,
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                "Email уже используется",
                "/api/users"
        );
    }

    @Test
    void handleForbiddenOperationReturns403() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleForbiddenOperation(
                        new ForbiddenOperationException(
                                "Нельзя изменить чужую организацию"
                        ),
                        request("/api/admin/organizations/1")
                );

        assertError(
                response,
                HttpStatus.FORBIDDEN,
                ApiErrorCode.FORBIDDEN,
                "Нельзя изменить чужую организацию",
                "/api/admin/organizations/1"
        );
    }

    @Test
    void handleExpiredRefreshTokenReturnsSpecific401() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleExpiredRefreshToken(
                        new ExpiredRefreshTokenException(
                                "internal expiry details"
                        ),
                        request("/api/auth/refresh")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.EXPIRED_REFRESH_TOKEN,
                "Refresh token истёк",
                "/api/auth/refresh"
        );

        assertThat(body.message())
                .doesNotContain("internal expiry details");
    }

    @Test
    void handleInvalidRefreshTokenReturnsSafe401() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleInvalidRefreshToken(
                        new InvalidRefreshTokenException(
                                "refresh token hash missing"
                        ),
                        request("/api/auth/refresh")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                "Недействительный refresh token",
                "/api/auth/refresh"
        );

        assertThat(body.message())
                .doesNotContain(
                        "hash",
                        "missing"
                );
    }

    @Test
    void handleRefreshTokenReuseReturnsGenericInvalidTokenResponse() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleRefreshTokenReuseDetected(
                        new RefreshTokenReuseDetectedException(
                                "Обнаружено повторное использование",
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID()
                        ),
                        request("/api/auth/refresh")
                );

        assertError(
                response,
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                "Недействительный refresh token",
                "/api/auth/refresh"
        );
    }

    @Test
    void handleGenericApiExceptionUsesPublicContract() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleApiException(
                        new TestApiException(),
                        request("/api/test")
                );

        assertError(
                response,
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                "Публичное сообщение",
                "/api/test"
        );
    }

    @Test
    void handleRateLimitExceededReturns429AndRetryAfterHeader() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleRateLimitExceeded(
                        new RateLimitExceededException(
                                "Превышен лимит",
                                Duration.ofSeconds(60)
                        ),
                        request("/api/chats")
                );

        assertError(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                "Превышен лимит",
                "/api/chats"
        );

        assertThat(response.getHeaders().getFirst(
                HttpHeaders.RETRY_AFTER
        )).isEqualTo("60");
    }

    @Test
    void handleRateLimitExceededOmitsRetryAfterForZeroDuration() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleRateLimitExceeded(
                        new RateLimitExceededException(
                                "Превышен лимит",
                                Duration.ZERO
                        ),
                        request("/api/chats")
                );

        assertError(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                "Превышен лимит",
                "/api/chats"
        );

        assertThat(response.getHeaders().getFirst(
                HttpHeaders.RETRY_AFTER
        )).isNull();
    }

    @Test
    void springOptimisticLockExceptionReturns409() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleOptimisticLock(
                        new OptimisticLockingFailureException(
                                "row was updated concurrently"
                        ),
                        request("/api/admin/users/1")
                );

        assertError(
                response,
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                "Данные были изменены другим пользователем. "
                        + "Обновите данные и повторите операцию",
                "/api/admin/users/1"
        );
    }

    @Test
    void jakartaOptimisticLockExceptionReturns409() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleOptimisticLock(
                        new OptimisticLockException(
                                "database internal details"
                        ),
                        request("/api/users/1")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                "Данные были изменены другим пользователем. "
                        + "Обновите данные и повторите операцию",
                "/api/users/1"
        );

        assertThat(body.message())
                .doesNotContain("database internal details");
    }

    @Test
    void handleValidationReturnsFieldErrorsAsLists()
            throws Exception {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(
                        new Object(),
                        "loginRequest"
                );

        bindingResult.addError(new FieldError(
                "loginRequest",
                "email",
                "Некорректный email"
        ));

        bindingResult.addError(new FieldError(
                "loginRequest",
                "password",
                "Пароль должен быть не короче 8 символов"
        ));

        bindingResult.addError(new FieldError(
                "loginRequest",
                "password",
                "Пароль должен содержать цифру"
        ));

        MethodArgumentNotValidException exception =
                validationException(bindingResult);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleValidation(
                        exception,
                        request("/api/auth/login")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "Ошибка валидации запроса",
                "/api/auth/login"
        );

        assertThat(body.fieldErrors())
                .containsKeys(
                        "email",
                        "password"
                );

        assertThat(body.fieldErrors().get("email"))
                .containsExactly(
                        "Некорректный email"
                );

        assertThat(body.fieldErrors().get("password"))
                .containsExactly(
                        "Пароль должен быть не короче 8 символов",
                        "Пароль должен содержать цифру"
                );
    }

    @Test
    void handleBindExceptionReturnsFieldAndGlobalErrors() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(
                        new Object(),
                        "request"
                );

        bindingResult.addError(new FieldError(
                "request",
                "dateFrom",
                "dateFrom обязателен"
        ));

        bindingResult.addError(new ObjectError(
                "request",
                "dateFrom должен быть раньше dateTo"
        ));

        ResponseEntity<ApiErrorResponse> response =
                handler.handleBindException(
                        new org.springframework.validation.BindException(
                                bindingResult
                        ),
                        request("/api/reports")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "Ошибка валидации запроса",
                "/api/reports"
        );

        assertThat(body.fieldErrors())
                .containsKeys(
                        "dateFrom",
                        "_global"
                );

        assertThat(body.fieldErrors().get("dateFrom"))
                .containsExactly(
                        "dateFrom обязателен"
                );

        assertThat(body.fieldErrors().get("_global"))
                .containsExactly(
                        "dateFrom должен быть раньше dateTo"
                );
    }

    @Test
    void returnValueMethodValidationReturnsSafe500() {
        HandlerMethodValidationException exception =
                mock(HandlerMethodValidationException.class);

        when(exception.isForReturnValue())
                .thenReturn(true);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleMethodValidation(
                        exception,
                        request("/api/test")
                );

        assertError(
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера",
                "/api/test"
        );
    }

    @Test
    void emptyArgumentMethodValidationUsesRequestFallback() {
        HandlerMethodValidationException exception =
                mock(HandlerMethodValidationException.class);

        List<ParameterValidationResult> parameterResults =
                List.of();

        List<MessageSourceResolvable> crossParameterResults =
                List.of();

        when(exception.isForReturnValue())
                .thenReturn(false);

        when(exception.getParameterValidationResults())
                .thenReturn(parameterResults);

        when(exception.getCrossParameterValidationResults())
                .thenReturn(crossParameterResults);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleMethodValidation(
                        exception,
                        request("/api/search")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "Ошибка валидации запроса",
                "/api/search"
        );

        assertThat(body.fieldErrors())
                .containsExactly(
                        Map.entry(
                                "request",
                                List.of(
                                        "Некорректное значение"
                                )
                        )
                );
    }

    @Test
    void handleConstraintViolationNormalizesPropertyPath() {
        ConstraintViolation<?> violation =
                mock(ConstraintViolation.class);

        Path path = mock(Path.class);

        Path.Node methodNode =
                mock(Path.Node.class);

        Path.Node parameterNode =
                mock(Path.Node.class);

        Path.Node propertyNode =
                mock(Path.Node.class);

        when(methodNode.getKind())
                .thenReturn(ElementKind.METHOD);

        when(parameterNode.getKind())
                .thenReturn(ElementKind.PARAMETER);

        when(parameterNode.getName())
                .thenReturn("request");

        when(propertyNode.getKind())
                .thenReturn(ElementKind.PROPERTY);

        when(propertyNode.getName())
                .thenReturn("page");

        when(path.iterator())
                .thenReturn(
                        List.of(
                                methodNode,
                                parameterNode,
                                propertyNode
                        ).iterator()
                );

        when(violation.getPropertyPath())
                .thenReturn(path);

        when(violation.getMessage())
                .thenReturn(
                        "должно быть больше или равно 0"
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleConstraintViolation(
                        new ConstraintViolationException(
                                Set.of(violation)
                        ),
                        request("/api/chats")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "Ошибка валидации запроса",
                "/api/chats"
        );

        assertThat(body.fieldErrors().get("page"))
                .containsExactly(
                        "должно быть больше или равно 0"
                );
    }

    @Test
    void unreadableBodyReturnsSafe400() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnreadableBody(
                        request("/api/users")
                );

        assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Некорректное тело запроса. "
                        + "Проверьте формат JSON и типы полей",
                "/api/users"
        );
    }

    @Test
    void missingParameterReturns400() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleMissingParameter(
                        new MissingServletRequestParameterException(
                                "dateFrom",
                                "Instant"
                        ),
                        request("/api/reports")
                );

        assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Обязательный параметр отсутствует: dateFrom",
                "/api/reports"
        );
    }

    @Test
    void missingHeaderReturns400() throws Exception {
        MethodParameter methodParameter =
                new MethodParameter(
                        GlobalExceptionHandlerTest.class
                                .getDeclaredMethod(
                                        "headerTarget",
                                        String.class
                                ),
                        0
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleMissingHeader(
                        new MissingRequestHeaderException(
                                HttpHeaders.AUTHORIZATION,
                                methodParameter
                        ),
                        request("/api/auth/logout")
                );

        assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Обязательный заголовок отсутствует: "
                        + HttpHeaders.AUTHORIZATION,
                "/api/auth/logout"
        );
    }

    @Test
    void typeMismatchReturnsParameterName() {
        MethodArgumentTypeMismatchException exception =
                mock(MethodArgumentTypeMismatchException.class);

        when(exception.getName())
                .thenReturn("page");

        ResponseEntity<ApiErrorResponse> response =
                handler.handleTypeMismatch(
                        exception,
                        request("/api/chats")
                );

        assertError(
                response,
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "Некорректное значение параметра: page",
                "/api/chats"
        );
    }

    @Test
    void methodNotAllowedReturns405AndPreservesAllowHeader() {
        HttpRequestMethodNotSupportedException exception =
                mock(HttpRequestMethodNotSupportedException.class);

        HttpHeaders exceptionHeaders = new HttpHeaders();
        exceptionHeaders.setAllow(
                Set.of(
                        HttpMethod.GET,
                        HttpMethod.POST
                )
        );

        when(exception.getHeaders())
                .thenReturn(exceptionHeaders);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleMethodNotAllowed(
                        exception,
                        request("/api/chats")
                );

        assertError(
                response,
                HttpStatus.METHOD_NOT_ALLOWED,
                ApiErrorCode.METHOD_NOT_ALLOWED,
                "HTTP-метод не поддерживается для этого ресурса",
                "/api/chats"
        );

        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(
                        HttpMethod.GET,
                        HttpMethod.POST
                );
    }

    @Test
    void unsupportedMediaTypeReturns415AndPreservesAcceptHeader() {
        HttpMediaTypeNotSupportedException exception =
                mock(HttpMediaTypeNotSupportedException.class);

        HttpHeaders exceptionHeaders = new HttpHeaders();
        exceptionHeaders.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        when(exception.getHeaders())
                .thenReturn(exceptionHeaders);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnsupportedMediaType(
                        exception,
                        request("/api/users")
                );

        assertError(
                response,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Тип содержимого запроса не поддерживается",
                "/api/users"
        );

        assertThat(response.getHeaders().getAccept())
                .containsExactly(
                        MediaType.APPLICATION_JSON
                );
    }

    @Test
    void notAcceptableReturns406AndPreservesAcceptHeader() {
        HttpMediaTypeNotAcceptableException exception =
                mock(HttpMediaTypeNotAcceptableException.class);

        HttpHeaders exceptionHeaders = new HttpHeaders();
        exceptionHeaders.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        when(exception.getHeaders())
                .thenReturn(exceptionHeaders);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleNotAcceptable(
                        exception,
                        request("/api/users")
                );

        assertError(
                response,
                HttpStatus.NOT_ACCEPTABLE,
                ApiErrorCode.NOT_ACCEPTABLE,
                "Запрошенный формат ответа не поддерживается",
                "/api/users"
        );

        assertThat(response.getHeaders().getAccept())
                .containsExactly(
                        MediaType.APPLICATION_JSON
                );
    }

    @Test
    void noResourceReturns404() {
        NoResourceFoundException exception =
                new NoResourceFoundException(
                        HttpMethod.GET,
                        "/missing-resource",
                        "missing-resource"
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleNoResource(
                        exception,
                        request("/missing-resource")
                );

        assertError(
                response,
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                "Ресурс не найден",
                "/missing-resource"
        );
    }

    @Test
    void responseStatus429MapsToRateLimitCode() {
        ResponseStatusException exception =
                new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Слишком много запросов"
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleResponseStatusException(
                        exception,
                        request("/api/chats")
                );

        assertError(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.RATE_LIMIT_EXCEEDED,
                "Слишком много запросов",
                "/api/chats"
        );
    }

    @Test
    void responseStatus5xxHidesReasonAndPreservesHeaders() {
        ResponseStatusException exception =
                mock(ResponseStatusException.class);

        HttpHeaders exceptionHeaders = new HttpHeaders();
        exceptionHeaders.set(
                HttpHeaders.RETRY_AFTER,
                "30"
        );

        when(exception.getStatusCode())
                .thenReturn(
                        HttpStatus.SERVICE_UNAVAILABLE
                );

        when(exception.getReason())
                .thenReturn(
                        "database-password=secret"
                );

        when(exception.getHeaders())
                .thenReturn(exceptionHeaders);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleResponseStatusException(
                        exception,
                        request("/api/test")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера",
                "/api/test"
        );

        assertThat(body.message())
                .doesNotContain(
                        "database-password",
                        "secret"
                );

        assertThat(response.getHeaders().getFirst(
                HttpHeaders.RETRY_AFTER
        )).isEqualTo("30");
    }

    @Test
    void expectedAuthenticationFailureReturnsGeneric401() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleExpectedAuthenticationFailure(
                        request("/api/auth/login")
                );

        assertError(
                response,
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "Неверные учётные данные",
                "/api/auth/login"
        );
    }

    @Test
    void expectedAuthenticationHandlerContainsExpectedTypes()
            throws Exception {
        Method method =
                GlobalExceptionHandler.class.getMethod(
                        "handleExpectedAuthenticationFailure",
                        HttpServletRequest.class
                );

        ExceptionHandler annotation =
                Objects.requireNonNull(
                        method.getAnnotation(
                                ExceptionHandler.class
                        )
                );

        assertThat(annotation.value())
                .containsExactlyInAnyOrder(
                        BadCredentialsException.class,
                        DisabledException.class,
                        LockedException.class,
                        AccountExpiredException.class,
                        CredentialsExpiredException.class
                );
    }

    @Test
    void authenticationInfrastructureFailureReturnsSafe503() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleAuthenticationInfrastructure(
                        new AuthenticationServiceException(
                                "LDAP password leaked"
                        ),
                        request("/api/auth/login")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                "Сервис авторизации временно недоступен",
                "/api/auth/login"
        );

        assertThat(body.message())
                .doesNotContain(
                        "LDAP",
                        "password"
                );
    }

    @Test
    void unexpectedAuthenticationFailureReturnsSafe500() {
        AuthenticationException exception =
                new AuthenticationException(
                        "unexpected secret detail"
                ) {
                };

        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnexpectedAuthenticationFailure(
                        exception,
                        request("/api/auth/login")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера",
                "/api/auth/login"
        );

        assertThat(body.message())
                .doesNotContain(
                        "unexpected",
                        "secret"
                );
    }

    @Test
    void unknownDataIntegrityViolationReturnsSafe500() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "constraint users_password_hash_key"
                        ),
                        request("/api/users")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера",
                "/api/users"
        );

        assertThat(body.message())
                .doesNotContain(
                        "constraint",
                        "password_hash"
                );
    }

    @Test
    void unexpectedExceptionReturnsSafe500() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnexpected(
                        new RuntimeException(
                                "database password leaked"
                        ),
                        request("/api/chats")
                );

        ApiErrorResponse body = assertError(
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера",
                "/api/chats"
        );

        assertThat(body.message())
                .doesNotContain(
                        "database",
                        "password",
                        "leaked"
                );
    }

    private ApiErrorResponse assertError(
            ResponseEntity<ApiErrorResponse> response,
            HttpStatus expectedStatus,
            ApiErrorCode expectedCode,
            String expectedMessage,
            String expectedPath
    ) {
        assertThat(response.getStatusCode())
                .isEqualTo(expectedStatus);

        assertThat(response.getHeaders().getFirst(
                HttpHeaders.CACHE_CONTROL
        )).isEqualTo("no-store");

        ApiErrorResponse body = requireBody(response);

        assertThat(body.timestamp())
                .isEqualTo(FIXED_TIME);

        assertThat(body.status())
                .isEqualTo(expectedStatus.value());

        assertThat(body.error())
                .isEqualTo(expectedCode.name());

        assertThat(body.message())
                .isEqualTo(expectedMessage);

        assertThat(body.path())
                .isEqualTo(expectedPath);

        assertThat(body.requestId())
                .isEqualTo(REQUEST_ID);

        assertThat(body.fieldErrors())
                .isNotNull();

        return body;
    }

    private ApiErrorResponse requireBody(
            ResponseEntity<ApiErrorResponse> response
    ) {
        return Objects.requireNonNull(
                response.getBody(),
                "ApiErrorResponse body must not be null"
        );
    }

    @SuppressWarnings("unused")
    private void validationTarget(Object request) {
    }

    @SuppressWarnings("unused")
    private void headerTarget(String authorizationHeader) {
    }

    private MockHttpServletRequest request(
            String uri
    ) {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(uri);

        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                REQUEST_ID
        );

        return request;
    }

    private static final class TestApiException
            extends ApiException {

        private TestApiException() {
            super(
                    HttpStatus.CONFLICT,
                    ApiErrorCode.CONFLICT,
                    "Публичное сообщение"
            );
        }
    }

    private MethodArgumentNotValidException validationException(
            BeanPropertyBindingResult bindingResult
    ) throws NoSuchMethodException {
        MethodParameter methodParameter =
                new MethodParameter(
                        GlobalExceptionHandlerTest.class
                                .getDeclaredMethod(
                                        "validationTarget",
                                        Object.class
                                ),
                        0
                );

        return new MethodArgumentNotValidException(
                methodParameter,
                bindingResult
        );
    }
}