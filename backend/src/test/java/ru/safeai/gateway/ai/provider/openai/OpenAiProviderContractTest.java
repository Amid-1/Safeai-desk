package ru.safeai.gateway.ai.provider.openai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderQuotaExceededException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.provider.AiContextWindowProperties;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.AiRetryProperties;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.NOW;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.OPERATION_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.request;

@Tag("contract")
@Timeout(10)
class OpenAiProviderContractTest {

    private static final String REQUESTED_MODEL =
            "gpt-4.1";

    private static final String ACTUAL_MODEL =
            "gpt-4.1-2026-07-15";

    private static final String CLIENT_REQUEST_ID_HEADER =
            "X-Client-Request-Id";

    private static final BigDecimal INPUT_PRICE =
            new BigDecimal("2");

    private static final BigDecimal OUTPUT_PRICE =
            new BigDecimal("8");

    private ProviderContractTestServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new ProviderContractTestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void completedResponseParsesAndUsesResolvedModelForPricing() {
        server.enqueue(
                200,
                Map.of(
                        "x-request-id",
                        "openai-request-123"
                ),
                completedResponse("Ответ")
        );

        AiChatResponse response =
                provider(
                        100_000,
                        disabledRetry(),
                        List.of(
                                price(
                                        ACTUAL_MODEL,
                                        "v-actual"
                                )
                        )
                ).sendMessage(request());

        assertThat(response.content())
                .isEqualTo("Ответ");

        assertThat(response.requestedModel())
                .isEqualTo(REQUESTED_MODEL);

        assertThat(response.model())
                .isEqualTo(ACTUAL_MODEL);

        assertThat(response.providerMessageId())
                .isEqualTo("resp_123");

        assertThat(response.providerRequestId())
                .isEqualTo("openai-request-123");

        assertThat(response.responseStatus())
                .isEqualTo(AiResponseStatus.COMPLETED);

        assertThat(response.finishReason())
                .isEqualTo("completed");

        assertThat(response.pricingStatus())
                .isEqualTo(PricingStatus.PRICED);

        assertThat(response.costUsd())
                .isEqualByComparingTo("0.006000000000");
    }

    @Test
    void cachedInputUsageIsNotReportedAsPricedByFlatPricingModel() {
        server.enqueue(
                200,
                """
                {
                  "id":"resp_cached",
                  "model":"%s",
                  "status":"completed",
                  "output_text":"Ответ",
                  "usage":{
                    "input_tokens":20000,
                    "input_tokens_details":{
                      "cached_tokens":15000,
                      "cache_write_tokens":0
                    },
                    "output_tokens":1000
                  }
                }
                """.formatted(ACTUAL_MODEL)
        );

        AiChatResponse response =
                provider(
                        100_000,
                        disabledRetry(),
                        List.of(
                                price(
                                        ACTUAL_MODEL,
                                        "v-actual"
                                )
                        )
                ).sendMessage(
                        request()
                );

        assertThat(response.inputTokens())
                .isEqualTo(20_000);

        assertThat(response.outputTokens())
                .isEqualTo(1_000);

        assertThat(response.pricingStatus())
                .isEqualTo(PricingStatus.UNPRICED);

        assertThat(response.costUsd())
                .isNull();

        assertThat(response.currency())
                .isNull();

        assertThat(response.priceVersion())
                .isNull();
    }

    @Test
    void incompleteResponsePreservesIncompleteDetailsReason() {
        server.enqueue(
                200,
                """
                {
                  "id":"resp_incomplete",
                  "model":"%s",
                  "status":"incomplete",
                  "incomplete_details":{"reason":"max_output_tokens"},
                  "output_text":"Частичный ответ",
                  "usage":{"input_tokens":10,"output_tokens":20}
                }
                """.formatted(ACTUAL_MODEL)
        );

        AiChatResponse response =
                provider().sendMessage(request());

        assertThat(response.responseStatus())
                .isEqualTo(AiResponseStatus.INCOMPLETE);

        assertThat(response.finishReason())
                .isEqualTo("max_output_tokens");
    }

    @Test
    void refusalIsRecognized() {
        server.enqueue(
                200,
                """
                {
                  "id":"resp_refusal",
                  "model":"%s",
                  "status":"completed",
                  "output":[{
                    "type":"message",
                    "content":[{
                      "type":"refusal",
                      "refusal":"Не могу выполнить этот запрос"
                    }]
                  }],
                  "usage":{"input_tokens":5,"output_tokens":7}
                }
                """.formatted(ACTUAL_MODEL)
        );

        AiChatResponse response =
                provider().sendMessage(request());

        assertThat(response.responseStatus())
                .isEqualTo(AiResponseStatus.REFUSED);

        assertThat(response.finishReason())
                .isEqualTo("refusal");

        assertThat(response.content())
                .isEqualTo("Не могу выполнить этот запрос");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "failed",
            "cancelled"
    })
    void failedAndCancelledAreProtocolErrors(
            String status
    ) {
        server.enqueue(
                200,
                responseWithStatus(status)
        );

        assertProtocolError(
                () -> provider()
                        .sendMessage(request())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "queued",
            "in_progress",
            "future_status"
    })
    void asyncAndUnknownStatusesAreNotCompleted(
            String status
    ) {
        server.enqueue(
                200,
                responseWithStatus(status)
        );

        assertProtocolError(
                () -> provider()
                        .sendMessage(request())
        );
    }

    @Test
    void maxResponseCharsIsEnforced() {
        server.enqueue(
                200,
                completedResponse("123456")
        );

        assertThatThrownBy(
                () -> provider(
                        5,
                        disabledRetry(),
                        List.of()
                ).sendMessage(request())
        )
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception ->
                        assertThat(
                                ((AiProviderException) exception)
                                        .getErrorType()
                        ).isEqualTo(
                                AiProviderErrorType.RESPONSE_TOO_LARGE
                        )
                );
    }

    @Test
    void responseModelWithoutPricingRuleIsUnpriced() {
        server.enqueue(
                200,
                completedResponse("Ответ")
        );

        AiChatResponse response =
                provider(
                        100_000,
                        disabledRetry(),
                        List.of(
                                price(
                                        REQUESTED_MODEL,
                                        "alias-v1"
                                )
                        )
                ).sendMessage(request());

        assertThat(response.model())
                .isEqualTo(ACTUAL_MODEL);

        assertThat(response.pricingStatus())
                .isEqualTo(PricingStatus.UNPRICED);

        assertThat(response.costUsd())
                .isNull();
    }

    @Test
    void storeFalseIsSentByDefault() {
        server.enqueue(
                200,
                completedResponse("Ответ")
        );

        provider().sendMessage(request());

        JsonNode body =
                server.singleRequest()
                        .jsonBody();

        assertThat(
                body.get("store").booleanValue()
        ).isFalse();

        assertThat(
                body.get("model").asString()
        ).isEqualTo(REQUESTED_MODEL);
    }

    @Test
    void transient429AndExhaustedQuotaAreDifferentFailures() {
        server.enqueue(
                429,
                Map.of(
                        "Retry-After",
                        "1"
                ),
                """
                {"error":{"type":"rate_limit_error",\
                "code":"rate_limit_exceeded","message":"slow down"}}
                """
        );

        assertThatThrownBy(
                () -> provider()
                        .sendMessage(request())
        )
                .isInstanceOf(
                        AiProviderRateLimitedException.class
                )
                .satisfies(exception -> {
                    AiProviderException providerException =
                            (AiProviderException) exception;

                    assertThat(
                            providerException
                                    .getProviderErrorCode()
                    ).isEqualTo(
                            "rate_limit_exceeded"
                    );

                    assertThat(
                            providerException
                                    .isRetryRecommended()
                    ).isTrue();
                });

        server.enqueue(
                429,
                """
                {"error":{"type":"insufficient_quota",\
                "code":"insufficient_quota","message":"no credits"}}
                """
        );

        assertThatThrownBy(
                () -> provider()
                        .sendMessage(request())
        )
                .isInstanceOf(
                        AiProviderQuotaExceededException.class
                )
                .satisfies(exception ->
                        assertThat(
                                ((AiProviderException) exception)
                                        .isRetryRecommended()
                        ).isFalse()
                );
    }

    @Test
    void retryUsesUniqueClientRequestIdPerHttpAttempt() {
        server.enqueue(
                429,
                Map.of(
                        "Retry-After",
                        "0"
                ),
                """
                {"error":{"type":"rate_limit_error",\
                "code":"rate_limit_exceeded"}}
                """
        );

        server.enqueue(
                200,
                completedResponse("Ответ после retry")
        );

        AiChatRequest request =
                request();

        AiChatResponse response =
                provider(
                        100_000,
                        enabledRetry(),
                        List.of()
                ).sendMessage(request);

        assertThat(response.content())
                .isEqualTo("Ответ после retry");

        assertThat(request.providerOperationId())
                .isEqualTo(OPERATION_ID);

        assertThat(server.requests())
                .hasSize(2);

        String firstId =
                Objects.requireNonNull(
                        server.requests()
                                .get(0)
                                .header(
                                        CLIENT_REQUEST_ID_HEADER
                                ),
                        "Первый X-Client-Request-Id отсутствует"
                );

        String secondId =
                Objects.requireNonNull(
                        server.requests()
                                .get(1)
                                .header(
                                        CLIENT_REQUEST_ID_HEADER
                                ),
                        "Второй X-Client-Request-Id отсутствует"
                );

        assertThat(UUID.fromString(firstId))
                .isNotNull();

        assertThat(UUID.fromString(secondId))
                .isNotNull();

        assertThat(firstId)
                .isNotEqualTo(secondId);

        assertThat(firstId)
                .isNotEqualTo(
                        OPERATION_ID.toString()
                );

        assertThat(secondId)
                .isNotEqualTo(
                        OPERATION_ID.toString()
                );
    }

    @Test
    void systemAndDeveloperInstructionsAreSentWithDistinctRoles() {
        server.enqueue(
                200,
                completedResponse("Ответ")
        );

        provider().sendMessage(request());

        JsonNode input =
                server.singleRequest()
                        .jsonBody()
                        .get("input");

        assertThat(
                input.get(0)
                        .get("role")
                        .asString()
        ).isEqualTo("system");

        assertThat(
                input.get(0)
                        .get("content")
                        .asString()
        ).isEqualTo(
                "Platform system policy"
        );

        assertThat(
                input.get(1)
                        .get("role")
                        .asString()
        ).isEqualTo("developer");

        assertThat(
                input.get(1)
                        .get("content")
                        .asString()
        ).isEqualTo(
                "Application developer policy"
        );

        assertThat(
                input.get(2)
                        .get("role")
                        .asString()
        ).isEqualTo("user");
    }

    private OpenAiProvider provider() {
        return provider(
                100_000,
                disabledRetry(),
                List.of()
        );
    }

    private OpenAiProvider provider(
            int maxResponseChars,
            AiRetryProperties retryProperties,
            List<ModelPricingProperties.ModelPrice> prices
    ) {
        OpenAiProperties properties =
                new OpenAiProperties(
                        "https://api.openai.com/v1",
                        "test-secret-key",
                        REQUESTED_MODEL,
                        64_000,
                        2_048,
                        maxResponseChars,
                        2L * 1024L * 1024L,
                        null,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2)
                );

        ModelPricingService pricingService =
                new ModelPricingService(
                        new ModelPricingProperties(
                                prices
                        ),
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC
                        )
                );

        RestClient client =
                AiRestClientFactory.create(
                        server.baseUrl(),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        2L * 1024L * 1024L
                );

        return new OpenAiProvider(
                properties,
                new AiResponseMetadataService(
                        pricingService
                ),
                new AiProviderRetryExecutor(
                        retryProperties
                ),
                new AiContextWindowService(
                        AiContextWindowProperties.defaults()
                ),
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                client
        );
    }

    private static AiRetryProperties disabledRetry() {
        return new AiRetryProperties(
                false,
                1,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5)
        );
    }

    private static AiRetryProperties enabledRetry() {
        return new AiRetryProperties(
                true,
                2,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5)
        );
    }

    private static ModelPricingProperties.ModelPrice price(
            String model,
            String version
    ) {
        return new ModelPricingProperties.ModelPrice(
                model,
                INPUT_PRICE,
                OUTPUT_PRICE,
                "USD",
                version
        );
    }

    private static String completedResponse(
            String content
    ) {
        return """
                {
                  "id":"resp_123",
                  "model":"%s",
                  "status":"completed",
                  "output_text":"%s",
                  "usage":{"input_tokens":1000,"output_tokens":500}
                }
                """.formatted(
                ACTUAL_MODEL,
                content
        );
    }

    private static String responseWithStatus(
            String status
    ) {
        return """
                {
                  "id":"resp_status",
                  "model":"%s",
                  "status":"%s",
                  "output_text":"Текст",
                  "usage":{"input_tokens":1,"output_tokens":1}
                }
                """.formatted(
                ACTUAL_MODEL,
                status
        );
    }

    private static void assertProtocolError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action
    ) {
        assertThatThrownBy(action)
                .isInstanceOf(
                        AiProviderException.class
                )
                .satisfies(exception ->
                        assertThat(
                                ((AiProviderException) exception)
                                        .getErrorType()
                        ).isEqualTo(
                                AiProviderErrorType.PROTOCOL_ERROR
                        )
                );
    }
}