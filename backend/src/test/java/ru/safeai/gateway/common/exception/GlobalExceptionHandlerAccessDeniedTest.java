package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization
        .AuthorizationDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAccessDeniedTest {

    private static final Instant NOW =
            Instant.parse("2026-07-23T12:00:00Z");

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        ApiErrorResponseFactory errorResponseFactory =
                new ApiErrorResponseFactory(
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC
                        )
                );

        handler = new GlobalExceptionHandler(
                errorResponseFactory
        );
    }

    @Test
    void handleAccessDeniedReturnsSafe403() {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET",
                        "/api/admin/audit-events"
                );

        request.setAttribute(
                "requestId",
                "request-123"
        );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleAccessDenied(
                        new AuthorizationDeniedException(
                                "Access Denied"
                        ),
                        request
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ApiErrorResponse body =
                response.getBody();

        assertThat(body)
                .isNotNull();

        assertThat(body.timestamp())
                .isEqualTo(NOW);

        assertThat(body.status())
                .isEqualTo(403);

        assertThat(body.error())
                .isEqualTo(
                        ApiErrorCode.FORBIDDEN.name()
                );

        assertThat(body.message())
                .isEqualTo("Доступ запрещён");

        assertThat(body.path())
                .isEqualTo(
                        "/api/admin/audit-events"
                );

        assertThat(body.message())
                .doesNotContain(
                        "Access Denied"
                );

        assertThat(body.fieldErrors())
                .isEmpty();
    }
}