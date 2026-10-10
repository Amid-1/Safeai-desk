package ru.safeai.gateway.chat.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

class ChatInfrastructureExceptionHandlerTest {

    private ChatInfrastructureExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatInfrastructureExceptionHandler(
                new ApiErrorResponseFactory(
                        Clock.fixed(
                                Instant.parse("2026-06-12T12:00:00Z"),
                                ZoneOffset.UTC
                        )
                )
        );
    }

    @Test
    void chatBusyPreservesApiErrorContract() {
        ResponseEntity<ApiErrorResponse> response = handler.handleChatBusy(
                new ChatBusyException("В этот чат уже отправляется сообщение"),
                request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo(ApiErrorCode.CHAT_BUSY.name());
        assertThat(body.message()).isEqualTo("В этот чат уже отправляется сообщение");
        assertThat(body.path()).isEqualTo("/api/chats/1/messages");
        assertThat(body.requestId()).isEqualTo("test-request-id");
    }

    @Test
    void chatLockUnavailablePreservesSafe503WithoutCauseLeak() {
        ResponseEntity<ApiErrorResponse> response = handler.handleChatLockUnavailable(
                new ChatLockUnavailableException(
                        "Redis lock failed",
                        new RuntimeException("redis-password=secret")
                ),
                request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(503);
        assertThat(body.error()).isEqualTo(ApiErrorCode.CHAT_LOCK_UNAVAILABLE.name());
        assertThat(body.message()).isEqualTo("Сервис блокировки чата временно недоступен");
        assertThat(body.message()).doesNotContain("Redis", "secret", "password");
        assertThat(body.path()).isEqualTo("/api/chats/1/messages");
        assertThat(body.requestId()).isEqualTo("test-request-id");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chats/1/messages");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "test-request-id");
        return request;
    }
}
