package ru.safeai.gateway.common.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new ApiErrorResponseFactory());
    }

    @Test
    void handleRateLimitExceeded_shouldReturn429AndRetryAfterHeader() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(
                        "Превышен лимит",
                        Duration.ofSeconds(60)
                ),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.message()).isEqualTo("Превышен лимит");
        assertThat(body.path()).isEqualTo("/api/chats");
        assertThat(body.requestId()).isEqualTo("test-request-id");
    }

    @Test
    void handleRateLimitExceeded_shouldNotAddRetryAfterWhenRetryAfterIsZero() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(
                        "Превышен лимит",
                        Duration.ZERO
                ),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void handleRateLimitUnavailable_shouldReturn503() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleRateLimitUnavailable(
                new RateLimitUnavailableException(
                        "Redis недоступен",
                        new RuntimeException("Redis down")
                ),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("RATE_LIMIT_UNAVAILABLE");
        assertThat(body.message()).isEqualTo("Сервис ограничения запросов временно недоступен");
        assertThat(body.path()).isEqualTo("/api/chats");
        assertThat(body.requestId()).isEqualTo("test-request-id");
    }

    @Test
    void handleValidation_shouldReturnFieldErrorsAsLists() throws Exception {
        MockHttpServletRequest request = request();

        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "loginRequest");

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

        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("validationTarget", Object.class),
                0
        );

        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleValidation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.message()).isEqualTo("Ошибка валидации запроса");

        Map<String, List<String>> fieldErrors = body.fieldErrors();

        assertThat(fieldErrors).containsKeys("email", "password");
        assertThat(fieldErrors.get("email"))
                .containsExactly("Некорректный email");

        assertThat(fieldErrors.get("password"))
                .containsExactlyInAnyOrder(
                        "Пароль должен быть не короче 8 символов",
                        "Пароль должен содержать цифру"
                );
    }

    @Test
    void handleBindException_shouldReturnFieldAndGlobalErrors() {
        MockHttpServletRequest request = request();

        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");

        bindingResult.addError(new FieldError(
                "request",
                "dateFrom",
                "dateFrom обязателен"
        ));

        bindingResult.addError(new ObjectError(
                "request",
                "dateFrom должен быть раньше dateTo"
        ));

        org.springframework.validation.BindException exception =
                new org.springframework.validation.BindException(bindingResult);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleBindException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("VALIDATION_ERROR");

        assertThat(body.fieldErrors()).containsKeys("dateFrom", "_global");
        assertThat(body.fieldErrors().get("dateFrom")).containsExactly("dateFrom обязателен");
        assertThat(body.fieldErrors().get("_global")).containsExactly("dateFrom должен быть раньше dateTo");
    }

    @Test
    void handleConstraintViolation_shouldReturnFieldErrors() {
        MockHttpServletRequest request = request();

        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);

        when(path.toString()).thenReturn("list.page");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("должно быть больше или равно 0");

        ConstraintViolationException exception =
                new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ApiErrorResponse> response =
                handler.handleConstraintViolation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.message()).isEqualTo("Ошибка валидации запроса");

        assertThat(body.fieldErrors()).containsKey("page");
        assertThat(body.fieldErrors().get("page"))
                .containsExactly("должно быть больше или равно 0");
    }

    @Test
    void handleMissingParameter_shouldReturn400() {
        MockHttpServletRequest request = request();

        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("dateFrom", "Instant");

        ResponseEntity<ApiErrorResponse> response =
                handler.handleMissingParameter(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("BAD_REQUEST");
        assertThat(body.message()).contains("dateFrom");
    }

    @Test
    void handleMissingHeader_shouldReturn400() throws Exception {
        MockHttpServletRequest request = request();

        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("headerTarget", String.class),
                0
        );

        MissingRequestHeaderException exception =
                new MissingRequestHeaderException("X-XSRF-TOKEN", methodParameter);

        ResponseEntity<ApiErrorResponse> response =
                handler.handleMissingHeader(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("BAD_REQUEST");
        assertThat(body.message()).contains("X-XSRF-TOKEN");
    }

    @Test
    void handleBadRequest_shouldReturn400() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleBadRequest(
                new BadRequestException("dateFrom должен быть раньше dateTo"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("BAD_REQUEST");
        assertThat(body.message()).isEqualTo("dateFrom должен быть раньше dateTo");
        assertThat(body.path()).isEqualTo("/api/chats");
        assertThat(body.requestId()).isEqualTo("test-request-id");
    }

    @Test
    void handleResourceNotFound_shouldReturn404() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleResourceNotFound(
                new ResourceNotFoundException("Чат не найден"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("NOT_FOUND");
        assertThat(body.message()).isEqualTo("Чат не найден");
    }

    @Test
    void handleConflict_shouldReturn409() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleConflict(
                new ConflictException("Email уже используется"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("CONFLICT");
        assertThat(body.message()).isEqualTo("Email уже используется");
    }

    @Test
    void handleForbiddenOperation_shouldReturn403() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response = handler.handleForbiddenOperation(
                new ForbiddenOperationException("Нельзя изменить чужую организацию"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("FORBIDDEN");
        assertThat(body.message()).isEqualTo("Нельзя изменить чужую организацию");
    }

    @Test
    void handleExpiredRefreshToken_shouldReturn401() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response =
                handler.handleExpiredRefreshToken(
                        new ExpiredRefreshTokenException("Refresh token истек"),
                        request
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("EXPIRED_REFRESH_TOKEN");
        assertThat(body.message()).isEqualTo("Refresh token истек");
    }

    @Test
    void handleInvalidRefreshToken_shouldReturn401() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response =
                handler.handleInvalidRefreshToken(
                        new InvalidRefreshTokenException("Refresh token не найден"),
                        request
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(body.message()).isEqualTo("Refresh token не найден");
    }

    @Test
    void handleRefreshTokenReuseDetected_shouldReturnGenericInvalidRefreshToken() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response =
                handler.handleRefreshTokenReuseDetected(
                        new RefreshTokenReuseDetectedException(
                                "Обнаружено повторное использование refresh token",
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID()
                        ),
                        request
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(body.message()).isEqualTo("Недействительный refresh token");
    }

    @Test
    void handleResponseStatusException_shouldReturnResponseStatus() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response =
                handler.handleResponseStatusException(
                        new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Слишком много запросов"
                        ),
                        request
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.message()).isEqualTo("Слишком много запросов");
    }

    @Test
    void handleUnexpected_shouldReturn500WithoutLeakingExceptionMessage() {
        MockHttpServletRequest request = request();

        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnexpected(
                        new RuntimeException("database password leaked"),
                        request
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(body.message()).isEqualTo("Внутренняя ошибка сервера");
        assertThat(body.message()).doesNotContain("database password leaked");
    }

    @SuppressWarnings("unused")
    private void validationTarget(Object request) {
    }

    @SuppressWarnings("unused")
    private void headerTarget(String csrfHeader) {
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chats");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "test-request-id");
        return request;
    }
}