package ru.safeai.gateway.ai.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.ai.execution.PhysicalAttemptReconciliationRequiredException;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    @Test
    void reconciliationRequiredHasDistinctNonRetryableApiState() {
        AiExceptionHandler handler =
                new AiExceptionHandler(
                        new ApiErrorResponseFactory(
                                Clock.fixed(
                                        Instant.parse(
                                                "2026-10-08T00:00:00Z"
                                        ),
                                        ZoneOffset.UTC
                                )
                        )
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(
                "/api/chats/turn"
        );

        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                "request-id"
        );

        PhysicalAttemptReconciliationRequiredException exception =
                new PhysicalAttemptReconciliationRequiredException(
                        UUID.fromString(
                                "11111111-1111-1111-1111-111111111111"
                        ),
                        new IllegalStateException(
                                "terminal evidence commit unknown"
                        )
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleReconciliationRequired(
                        exception,
                        request
                );

        assertThat(
                response.getStatusCode()
        ).isEqualTo(
                HttpStatus.CONFLICT
        );

        assertThat(
                response.getBody()
        ).isNotNull();

        assertThat(
                response.getBody()
                        .error()
        ).isEqualTo(
                ApiErrorCode
                        .AI_EXECUTION_RECONCILIATION_REQUIRED
                        .name()
        );
    }
}
