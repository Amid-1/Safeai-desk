package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAvailabilityExceptionHandlerTest {

    private ChatAvailabilityExceptionHandler handler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-06-12T12:00:00Z"),
                ZoneOffset.UTC
        );

        handler = new ChatAvailabilityExceptionHandler(
                new ApiErrorResponseFactory(clock)
        );
    }

    @Test
    void handleChatBusyReturns409AndStableCode() {
        MockHttpServletRequest request = request();

        var response = handler.handleChatBusy(
                new ChatBusyException(
                        "В этот чат уже отправляется сообщение"
                ),
                request
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
                .isEqualTo("CHAT_BUSY");
        assertThat(response.getBody().message())
                .isEqualTo("В этот чат уже отправляется сообщение");
    }

    @Test
    void handleChatLockUnavailableReturns503AndStableCode() {
        MockHttpServletRequest request = request();

        var response = handler.handleChatLockUnavailable(request);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
                .isEqualTo("CHAT_LOCK_UNAVAILABLE");
        assertThat(response.getBody().message())
                .isEqualTo(
                        "Сервис блокировки чата временно недоступен"
                );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI("/api/chats/1/messages");
        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                "test-request-id"
        );

        return request;
    }
}
