package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.ai.exception.AiContextLimitException;
import ru.safeai.gateway.ai.exception.AiProviderBillingException;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderQuotaExceededException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.web.AiExceptionHandler;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AiExceptionHandlerTest {

    private static final Instant NOW =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final String REQUEST_URI =
            "/api/chat";

    private final AiExceptionHandler handler =
            new AiExceptionHandler(
                    new ApiErrorResponseFactory(
                            Clock.fixed(
                                    NOW,
                                    ZoneOffset.UTC
                            )
                    )
            );

    @Test
    void rateLimitedReturns429RoundedRetryAfterAndNoStore() {
        var response =
                handler.handleRateLimited(
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
                        request()
                );

        var body =
                Objects.requireNonNull(
                        response.getBody(),
                        "Response body не должен быть null"
                );

        assertThat(response.getStatusCode())
                .isEqualTo(
                        HttpStatus.TOO_MANY_REQUESTS
                );

        assertThat(
                response.getHeaders()
                        .getFirst(HttpHeaders.RETRY_AFTER)
        ).isEqualTo("2");

        assertThat(
                response.getHeaders()
                        .getCacheControl()
        ).isEqualTo(
                CacheControl.noStore()
                        .getHeaderValue()
        );

        assertThat(body.error())
                .isEqualTo(
                        "AI_PROVIDER_RATE_LIMITED"
                );
    }

    @Test
    void overloadedReturns503AndDedicatedCode() {
        var response =
                handler.handleOverloaded(
                        new AiProviderOverloadedException(
                                "anthropic",
                                "claude-sonnet",
                                529,
                                "provider-request-id",
                                Duration.ofSeconds(2),
                                "overloaded",
                                null
                        ),
                        request()
                );

        var body =
                Objects.requireNonNull(
                        response.getBody(),
                        "Response body не должен быть null"
                );

        assertThat(response.getStatusCode())
                .isEqualTo(
                        HttpStatus.SERVICE_UNAVAILABLE
                );

        assertThat(body.error())
                .isEqualTo(
                        "AI_PROVIDER_OVERLOADED"
                );
    }

    @Test
    void quotaAndBillingDoNotExposeProviderInternals() {
        var quotaResponse =
                handler.handleProviderAccountFailure(
                        new AiProviderQuotaExceededException(
                                "openai",
                                "gpt-4.1",
                                429,
                                "request-id",
                                "insufficient_quota",
                                "internal quota detail",
                                null
                        ),
                        request()
                );

        var billingResponse =
                handler.handleProviderAccountFailure(
                        new AiProviderBillingException(
                                "anthropic",
                                "claude-sonnet",
                                402,
                                "request-id",
                                "billing_error",
                                "internal billing detail",
                                null
                        ),
                        request()
                );

        var quotaBody =
                Objects.requireNonNull(
                        quotaResponse.getBody(),
                        "Quota response body не должен быть null"
                );

        var billingBody =
                Objects.requireNonNull(
                        billingResponse.getBody(),
                        "Billing response body не должен быть null"
                );

        assertThat(quotaResponse.getStatusCode())
                .isEqualTo(
                        HttpStatus.SERVICE_UNAVAILABLE
                );

        assertThat(billingResponse.getStatusCode())
                .isEqualTo(
                        HttpStatus.SERVICE_UNAVAILABLE
                );

        assertThat(quotaBody.message())
                .doesNotContain(
                        "internal quota detail"
                );

        assertThat(billingBody.message())
                .doesNotContain(
                        "internal billing detail"
                );
    }

    @Test
    void contextLimitReturnsControlled400() {
        var response =
                handler.handleContextLimit(
                        new AiContextLimitException(
                                "too large"
                        ),
                        request()
                );

        var body =
                Objects.requireNonNull(
                        response.getBody(),
                        "Response body не должен быть null"
                );

        assertThat(response.getStatusCode())
                .isEqualTo(
                        HttpStatus.BAD_REQUEST
                );

        assertThat(body.message())
                .contains(
                        "AI-контекст"
                );
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(
                REQUEST_URI
        );

        return request;
    }
}