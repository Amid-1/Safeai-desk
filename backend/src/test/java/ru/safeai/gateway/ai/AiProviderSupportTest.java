package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.provider.AiProviderSupport;
import ru.safeai.gateway.ai.provider.AiResponseTooLargeIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AiProviderSupportTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void tokenExtractionPreservesMissingRealZeroAndFallbackNames() {
        JsonNode missing = mapper.readTree("{\"id\":\"resp\"}");
        JsonNode zero = mapper.readTree("""
                {"usage":{"input_tokens":0,"output_tokens":0}}
                """);
        JsonNode fallback = mapper.readTree("""
                {"usage":{"prompt_tokens":12,"completion_tokens":34}}
                """);

        assertThat(AiProviderSupport.extractInputTokens(missing)).isNull();
        assertThat(AiProviderSupport.extractOutputTokens(missing)).isNull();
        assertThat(AiProviderSupport.extractInputTokens(zero)).isZero();
        assertThat(AiProviderSupport.extractOutputTokens(zero)).isZero();
        assertThat(AiProviderSupport.extractInputTokens(fallback))
                .isEqualTo(12);
        assertThat(AiProviderSupport.extractOutputTokens(fallback))
                .isEqualTo(34);
    }

    @Test
    void extractsOpenAiSpecializedBillingDimensions() {
        JsonNode response = mapper.readTree("""
                {
                  "usage":{
                    "input_tokens":20000,
                    "input_tokens_details":{
                      "cached_tokens":15000,
                      "cache_write_tokens":2000
                    },
                    "output_tokens":1000
                  }
                }
                """);

        AiTokenUsage usage =
                AiProviderSupport.extractTokenUsage(
                        response
                );

        assertThat(usage.inputTokens())
                .isEqualTo(20_000);

        assertThat(usage.cachedInputTokens())
                .isEqualTo(15_000);

        assertThat(usage.cacheWriteInputTokens())
                .isEqualTo(2_000);

        assertThat(usage.outputTokens())
                .isEqualTo(1_000);

        assertThat(
                usage.specializedBillingDimensionsPresent()
        ).isTrue();

        assertThat(
                usage.specializedBillingDimensionsValid()
        ).isTrue();

        assertThat(
                usage.hasSpecializedBillableTokens()
        ).isTrue();
    }

    @Test
    void extractsAnthropicCacheBillingDimensions() {
        JsonNode response = mapper.readTree("""
                {
                  "usage":{
                    "input_tokens":100,
                    "cache_read_input_tokens":80,
                    "cache_creation_input_tokens":20,
                    "output_tokens":10
                  }
                }
                """);

        AiTokenUsage usage =
                AiProviderSupport.extractTokenUsage(
                        response
                );

        assertThat(usage.cachedInputTokens())
                .isEqualTo(80);

        assertThat(usage.cacheWriteInputTokens())
                .isEqualTo(20);

        assertThat(
                usage.specializedBillingDimensionsPresent()
        ).isTrue();

        assertThat(
                usage.specializedBillingDimensionsValid()
        ).isTrue();
    }

    @Test
    void malformedSpecializedBillingDimensionIsNotTreatedAsAbsent() {
        JsonNode response = mapper.readTree("""
                {
                  "usage":{
                    "input_tokens":100,
                    "input_tokens_details":{
                      "cached_tokens":"not-a-number"
                    },
                    "output_tokens":10
                  }
                }
                """);

        AiTokenUsage usage =
                AiProviderSupport.extractTokenUsage(
                        response
                );

        assertThat(
                usage.specializedBillingDimensionsPresent()
        ).isTrue();

        assertThat(
                usage.specializedBillingDimensionsValid()
        ).isFalse();

        assertThat(usage.cachedInputTokens())
                .isNull();
    }

    @Test
    void zeroSpecializedBillingDimensionsRemainObservableButNotBillable() {
        JsonNode response = mapper.readTree("""
                {
                  "usage":{
                    "input_tokens":100,
                    "input_tokens_details":{
                      "cached_tokens":0,
                      "cache_write_tokens":0
                    },
                    "output_tokens":10
                  }
                }
                """);

        AiTokenUsage usage =
                AiProviderSupport.extractTokenUsage(
                        response
                );

        assertThat(
                usage.specializedBillingDimensionsPresent()
        ).isTrue();

        assertThat(
                usage.specializedBillingDimensionsValid()
        ).isTrue();

        assertThat(
                usage.hasSpecializedBillableTokens()
        ).isFalse();
    }

    @Test
    void providerIndependentInstructionsMapCorrectly() {
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "system",
                "developer",
                "new-user",
                List.of(
                        new AiMessage(AiMessageRole.USER, "old-user"),
                        new AiMessage(AiMessageRole.ASSISTANT, "old-answer")
                )
        );

        assertThat(AiProviderSupport.buildOpenAiInput(request))
                .extracting(item -> item.get("role"))
                .containsExactly(
                        "system",
                        "developer",
                        "user",
                        "assistant",
                        "user"
                );

        assertThat(AiProviderSupport.buildAnthropicSystem(request))
                .extracting(item -> item.get("text"))
                .containsExactly("system", "developer");

        assertThat(AiProviderSupport.buildAnthropicMessages(request))
                .extracting(item -> item.get("role"))
                .containsExactly("user", "assistant", "user");
    }

    @Test
    void extractsProviderErrorAndRequestIdWithoutLeakingBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-request-id", "request-123");

        HttpClientErrorException exception =
                HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests",
                        headers,
                        """
                        {"error":{"type":"rate_limit_error",\
                        "code":"rate_limit_exceeded",\
                        "message":"slow down"}}
                        """.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                );

        var details = AiProviderSupport.extractProviderError(exception);

        assertThat(details.type()).isEqualTo("rate_limit_error");
        assertThat(details.code()).isEqualTo("rate_limit_exceeded");
        assertThat(details.message()).isEqualTo("slow down");
        assertThat(AiProviderSupport.extractProviderRequestId(exception))
                .isEqualTo("request-123");
    }

    @Test
    void retryAfterSupportsSecondsAndHttpDate() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-01T12:00:00Z"),
                ZoneOffset.UTC
        );

        HttpHeaders seconds = new HttpHeaders();
        seconds.set(HttpHeaders.RETRY_AFTER, "7");
        HttpClientErrorException secondsException =
                HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "",
                        seconds,
                        new byte[0],
                        StandardCharsets.UTF_8
                );

        assertThat(AiProviderSupport.extractRetryAfter(
                secondsException,
                clock
        )).isEqualTo(Duration.ofSeconds(7));

        HttpHeaders date = new HttpHeaders();
        date.set(
                HttpHeaders.RETRY_AFTER,
                "Sat, 1 Aug 2026 12:00:05 GMT"
        );
        HttpClientErrorException dateException =
                HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "",
                        date,
                        new byte[0],
                        StandardCharsets.UTF_8
                );

        assertThat(AiProviderSupport.extractRetryAfter(
                dateException,
                clock
        )).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void networkFailureKindsAreDistinguished() {
        assertThat(AiProviderSupport.isConnectFailure(
                new ResourceAccessException(
                        "connect",
                        new ConnectException("refused")
                )
        )).isTrue();

        assertThat(AiProviderSupport.isReadTimeout(
                new ResourceAccessException(
                        "timeout",
                        new SocketTimeoutException("read timed out")
                )
        )).isTrue();

        assertThat(AiProviderSupport.isResponseTooLarge(
                new ResourceAccessException(
                        "large",
                        new AiResponseTooLargeIOException(10)
                )
        )).isTrue();
    }
}
