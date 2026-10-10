package ru.safeai.gateway.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthExceptionHandlerTest {

    private static final Instant FIXED_TIME =
            Instant.parse("2026-06-12T12:00:00Z");
    private static final String REQUEST_ID = "test-request-id";

    private AuthExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AuthExceptionHandler(
                new ApiErrorResponseFactory(
                        Clock.fixed(FIXED_TIME, ZoneOffset.UTC)
                )
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

    private ApiErrorResponse assertError(
            ResponseEntity<ApiErrorResponse> response,
            HttpStatus expectedStatus,
            ApiErrorCode expectedCode,
            String expectedMessage,
            String expectedPath
    ) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");

        ApiErrorResponse body = Objects.requireNonNull(
                response.getBody(), "ApiErrorResponse body must not be null"
        );
        assertThat(body.timestamp()).isEqualTo(FIXED_TIME);
        assertThat(body.status()).isEqualTo(expectedStatus.value());
        assertThat(body.error()).isEqualTo(expectedCode.name());
        assertThat(body.message()).isEqualTo(expectedMessage);
        assertThat(body.path()).isEqualTo(expectedPath);
        assertThat(body.requestId()).isEqualTo(REQUEST_ID);
        assertThat(body.fieldErrors()).isNotNull();
        return body;
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                REQUEST_ID
        );
        return request;
    }
}
