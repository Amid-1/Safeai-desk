package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    @SuppressWarnings("unused")
    private void validationTarget(Object request) {
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chats");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "test-request-id");
        return request;
    }
}