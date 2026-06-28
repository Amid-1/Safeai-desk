package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.web.AiExceptionHandler;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    private final ApiErrorResponseFactory errorResponseFactory =
            new ApiErrorResponseFactory();

    private final AiExceptionHandler handler =
            new AiExceptionHandler(errorResponseFactory);

    @Test
    void handleAiProviderRateLimited_shouldReturnServiceUnavailableResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chat");

        var response = handler.handleAiProviderRateLimited(
                new AiProviderRateLimitedException(
                        "openai",
                        "gpt-4.1",
                        429,
                        "test-request-id",
                        "OpenAI API rate limited: status=429",
                        new RuntimeException("provider 429")
                ),
                request
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ApiErrorResponse body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("AI_PROVIDER_RATE_LIMITED");
        assertThat(body.message()).isEqualTo("AI provider временно ограничил запросы");
        assertThat(body.status()).isEqualTo(503);
        assertThat(body.path()).isEqualTo("/api/chat");
    }
}