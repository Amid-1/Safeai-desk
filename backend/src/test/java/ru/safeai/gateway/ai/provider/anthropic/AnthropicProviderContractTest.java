package ru.safeai.gateway.ai.provider.anthropic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderBillingException;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.provider.*;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.NOW;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.request;

@Tag("contract")
@Timeout(10)
class AnthropicProviderContractTest {

    private static final String REQUESTED_MODEL = "claude-sonnet";
    private static final String ACTUAL_MODEL =
            "claude-sonnet-4-20260514";

    private ProviderContractTestServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new ProviderContractTestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({
            "end_turn, COMPLETED",
            "stop_sequence, COMPLETED",
            "max_tokens, INCOMPLETE",
            "model_context_window_exceeded, INCOMPLETE",
            "pause_turn, INCOMPLETE",
            "refusal, REFUSED"
    })
    void mapsDocumentedStopReasons(
            String stopReason,
            AiResponseStatus expectedStatus
    ) {
        server.enqueue(
                200,
                Map.of("request-id", "anthropic-request-123"),
                response(stopReason, "Ответ")
        );

        AiChatResponse result = provider(
                List.of(price(ACTUAL_MODEL))
        ).sendMessage(request());

        assertThat(result.responseStatus()).isEqualTo(expectedStatus);
        assertThat(result.finishReason()).isEqualTo(stopReason);
        assertThat(result.requestedModel()).isEqualTo(REQUESTED_MODEL);
        assertThat(result.model()).isEqualTo(ACTUAL_MODEL);
        assertThat(result.providerRequestId())
                .isEqualTo("anthropic-request-123");
        assertThat(result.pricingStatus())
                .isEqualTo(PricingStatus.PRICED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool_use", "future_stop_reason"})
    void toolUseAndUnknownStopReasonAreProtocolErrors(
            String stopReason
    ) {
        server.enqueue(200, response(stopReason, "Текст"));

        assertThatThrownBy(() -> provider().sendMessage(request()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception -> assertThat(
                        ((AiProviderException) exception).getErrorType()
                ).isEqualTo(AiProviderErrorType.PROTOCOL_ERROR));
    }

    @Test
    void developerInstructionsArePreservedInTopLevelSystemBlocks() {
        server.enqueue(200, response("end_turn", "Ответ"));

        provider().sendMessage(request());

        JsonNode body = server.singleRequest().jsonBody();
        JsonNode system = body.get("system");

        assertThat(system.isArray()).isTrue();
        assertThat(system).hasSize(2);
        assertThat(system.get(0).get("type").asString())
                .isEqualTo("text");
        assertThat(system.get(0).get("text").asString())
                .isEqualTo("Platform system policy");
        assertThat(system.get(1).get("text").asString())
                .isEqualTo("Application developer policy");

        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asString())
                .isEqualTo("user");
    }

    @Test
    void maxResponseCharsIsEnforced() {
        server.enqueue(200, response("end_turn", "123456"));

        assertThatThrownBy(() -> provider(
                5,
                List.of()
        ).sendMessage(request()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception -> assertThat(
                        ((AiProviderException) exception).getErrorType()
                ).isEqualTo(AiProviderErrorType.RESPONSE_TOO_LARGE));
    }

    @Test
    void rateLimitBillingAndOverloadAreClassifiedSeparately() {
        server.enqueue(429, """
                {"type":"error","error":{
                  "type":"rate_limit_error","message":"slow down"
                }}
                """);

        assertThatThrownBy(() -> provider().sendMessage(request()))
                .isInstanceOf(AiProviderRateLimitedException.class);

        server.enqueue(402, """
                {"type":"error","error":{
                  "type":"billing_error","message":"billing disabled"
                }}
                """);

        assertThatThrownBy(() -> provider().sendMessage(request()))
                .isInstanceOf(AiProviderBillingException.class);

        server.enqueue(529, """
                {"type":"error","error":{
                  "type":"overloaded_error","message":"overloaded"
                }}
                """);

        assertThatThrownBy(() -> provider().sendMessage(request()))
                .isInstanceOf(AiProviderOverloadedException.class);
    }

    @Test
    void unknownResolvedModelIsUnpriced() {
        server.enqueue(200, response("end_turn", "Ответ"));

        AiChatResponse result = provider(
                List.of(price(REQUESTED_MODEL))
        ).sendMessage(request());

        assertThat(result.model()).isEqualTo(ACTUAL_MODEL);
        assertThat(result.pricingStatus())
                .isEqualTo(PricingStatus.UNPRICED);
    }

    private AnthropicProvider provider() {
        return provider(100_000, List.of());
    }

    private AnthropicProvider provider(
            List<ModelPricingProperties.ModelPrice> prices
    ) {
        return provider(100_000, prices);
    }

    private AnthropicProvider provider(
            int maxResponseChars,
            List<ModelPricingProperties.ModelPrice> prices
    ) {
        AnthropicProperties properties = new AnthropicProperties(
                "https://api.anthropic.com/v1",
                "test-secret-key",
                REQUESTED_MODEL,
                "2023-06-01",
                64_000,
                2_048,
                maxResponseChars,
                2L * 1024L * 1024L,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        );

        ModelPricingService pricingService =
                new ModelPricingService(
                        new ModelPricingProperties(prices),
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );

        AiRetryProperties retryProperties = new AiRetryProperties(
                false,
                1,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5)
        );

        RestClient client = AiRestClientFactory.create(
                server.baseUrl(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                2L * 1024L * 1024L
        );

        return new AnthropicProvider(
                properties,
                new AiResponseMetadataService(pricingService),
                new AiProviderRetryExecutor(retryProperties),
                new AiContextWindowService(
                        AiContextWindowProperties.defaults()
                ),
                Clock.fixed(NOW, ZoneOffset.UTC),
                client
        );
    }

    private ModelPricingProperties.ModelPrice price(String model) {
        return new ModelPricingProperties.ModelPrice(
                model,
                new BigDecimal("3"),
                new BigDecimal("15"),
                "USD",
                "anthropic-v1"
        );
    }

    private String response(
            String stopReason,
            String text
    ) {
        return """
                {
                  "id":"msg_123",
                  "model":"%s",
                  "stop_reason":"%s",
                  "content":[{"type":"text","text":"%s"}],
                  "usage":{"input_tokens":1000,"output_tokens":500}
                }
                """.formatted(ACTUAL_MODEL, stopReason, text);
    }
}
