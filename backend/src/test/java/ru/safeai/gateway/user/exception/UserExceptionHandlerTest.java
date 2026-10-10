package ru.safeai.gateway.user.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserExceptionHandlerTest {

    @Test
    void versionConflictPreservesHttpContractAndSafePublicMessage() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-10T12:00:00Z"),
                ZoneOffset.UTC
        );
        UserExceptionHandler advice = new UserExceptionHandler(
                new ApiErrorResponseFactory(clock)
        );
        UUID entityId = UUID.randomUUID();
        UserVersionConflictException exception = new UserVersionConflictException(
                entityId, 5L, 6L
        );
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/users/test");
        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                "version-test-request-id"
        );

        var response = advice.handleUserVersionConflict(exception, request);
        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("USER_VERSION_CONFLICT");
        assertThat(body.path()).isEqualTo("/api/users/test");
        assertThat(body.requestId()).isEqualTo("version-test-request-id");
        assertThat(body.timestamp()).isEqualTo(clock.instant());
        assertThat(body.fieldErrors()).isNotNull();
        assertThat(body.message()).isEqualTo(exception.getPublicMessage());
        assertThat(body.message()).doesNotContain(
                "expectedVersion", "actualVersion", entityId.toString()
        );
    }
}
