package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.web.AiExceptionHandler;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    private static final Instant NOW =
            Instant.parse("2026-06-12T12:00:00Z");

    private final AiExceptionHandler handler =
            new AiExceptionHandler(
                    new ApiErrorResponseFactory(
                            Clock.fixed(NOW, ZoneOffset.UTC)
                    )
            );

    @Test
    void rateLimitedReturns429AndRetryAfter() {
        MockHttpServletRequest request = request();

        var response = handler.handleRateLimited(
                new AiProviderRateLimitedException(
                        "openai",
                        "gpt-4.1",
                        429,
                        "provider-request-id",
                        Duration.ofMillis(1_500),
                        false,
                        "rate limited",
                        null
                ),
                request
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
                .isEqualTo("AI_PROVIDER_RATE_LIMITED");
        assertThat(response.getBody().timestamp()).isEqualTo(NOW);
    }

    @Test
    void overloadedReturns503AndDedicatedCode() {
        var response = handler.handleOverloaded(
                new AiProviderOverloadedException(
                        "anthropic",
                        "claude-opus-4-8",
                        529,
                        "provider-request-id",
                        Duration.ofSeconds(2),
                        "overloaded",
                        null
                ),
                request()
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
                .isEqualTo("AI_PROVIDER_OVERLOADED");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.setRequestURI("/api/chat");
        return request;
    }
}
