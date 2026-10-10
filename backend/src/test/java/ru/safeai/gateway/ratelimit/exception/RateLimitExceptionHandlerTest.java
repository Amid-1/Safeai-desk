package ru.safeai.gateway.ratelimit.exception;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceptionHandlerTest {

    private static final Instant FIXED_TIME =
            Instant.parse("2026-06-12T12:00:00Z");
    private static final String REQUEST_ID = "test-request-id";

    private RateLimitExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RateLimitExceptionHandler(
                new ApiErrorResponseFactory(
                        Clock.fixed(FIXED_TIME, ZoneOffset.UTC)
                )
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
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
        return request;
    }
}
