package ru.safeai.gateway.ai.provider.openai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.execution.OutcomeCertainty;
import ru.safeai.gateway.ai.execution.ProviderFailureCertainty;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.pricing.StaticConfigurationPricingResolver;
import ru.safeai.gateway.ai.provider.AiContextWindowProperties;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.AiRetryProperties;
import ru.safeai.gateway.ai.testsupport.AiTestFixtures;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Additional real-HTTP tests; existing OpenAiProviderContractTest is preserved. */
@Tag("contract")
@Timeout(10)
class OpenAiWireGovernanceContractTest {
    private static final String SECRET = "dummy-openai-token-do-not-log";

    @Test
    void sendsOperationSpecificPhysicalRequestIdAndTheRouteBoundOutputLimit()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, Map.of("x-request-id", "provider-request-7"), success());
            AiChatResponse result = provider(server, false).sendMessage(governed());
            var request = server.singleRequest();
            assertThat(request.path()).isEqualTo("/responses");
            assertThat(request.header("Authorization")).isEqualTo("Bearer " + SECRET);
            assertThat(request.header("X-Client-Request-Id")).isNotBlank();
            String clientRequestId = java.util.Objects.requireNonNull(
                    request.header("X-Client-Request-Id"),
                    "OpenAI contract must include a physical client request ID");
            assertThat(UUID.fromString(clientRequestId))
                    .isNotEqualTo(AiTestFixtures.OPERATION_ID);
            assertThat(request.jsonBody().get("max_output_tokens").intValue()).isEqualTo(128);
            assertThat(request.jsonBody().get("store").booleanValue()).isFalse();
            assertThat(result.providerRequestId()).isEqualTo("provider-request-7");
        }
    }

    @Test
    void exactlyOneRetryAfterExplicit429UsesDifferentPhysicalId()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(429, Map.of("Retry-After", "0"),
                    "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}");
            server.enqueue(200, success());
            assertThat(provider(server, true).sendMessage(governed()).content())
                    .isEqualTo("ok");
            assertThat(server.requests()).hasSize(2);
            assertThat(server.requests().get(0).header("X-Client-Request-Id"))
                    .isNotEqualTo(server.requests().get(1).header("X-Client-Request-Id"));
            assertThat(server.requests().get(0).jsonBody().get("model").asString())
                    .isEqualTo(server.requests().get(1).jsonBody().get("model").asString());
        }
    }

    @Test
    void http503IsAmbiguousAndCannotBeBlindlyRetried() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(503, Map.of("x-request-id", "provider-503"),
                    "{\"error\":{\"type\":\"server_error\",\"message\":\"temporary\"}}");
            assertThatThrownBy(() -> provider(server, true).sendMessage(governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(throwable -> {
                        AiProviderException error = (AiProviderException) throwable;
                        assertThat(error.getStatusCode()).isEqualTo(503);
                        assertThat(ProviderFailureCertainty.classify(error))
                                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
                    });
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void malformedHttp200DoesNotGetTurnedIntoSuccessfulAnswerOrBlindRetry()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "not valid json");
            assertThatThrownBy(() -> provider(server, true).sendMessage(governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(throwable -> assertThat(ProviderFailureCertainty.classify(
                            (AiProviderException) throwable)).isEqualTo(OutcomeCertainty.AMBIGUOUS));
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void providerErrorBodyAndCredentialsAreNotCopiedIntoPublicExceptionMessage()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(401, "{\"error\":{\"message\":\"" + SECRET + " sensitive body\"}}");
            assertThatThrownBy(() -> provider(server, false).sendMessage(governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(throwable -> {
                        AiProviderException error = (AiProviderException) throwable;
                        assertThat(error.getMessage()).doesNotContain(SECRET, "sensitive body");
                        assertThat(error.getStatusCode()).isEqualTo(401);
                    });
            assertThat(server.requests()).hasSize(1);
        }
    }

    private static OpenAiProvider provider(ProviderContractTestServer server,
                                           boolean retryEnabled) {
        OpenAiProperties props = new OpenAiProperties("https://api.openai.com/v1",
                SECRET, "gpt-4.1", 64_000, 2_048, 100_000, 65_536L,
                false, Duration.ofSeconds(1), Duration.ofSeconds(2));
        Clock clock = Clock.fixed(AiTestFixtures.NOW, ZoneOffset.UTC);
        ModelPricingService prices = new ModelPricingService(
                new ModelPricingProperties(List.of()), clock);
        return new OpenAiProvider(props,
                new AiResponseMetadataService(new StaticConfigurationPricingResolver(prices)),
                new AiProviderRetryExecutor(new AiRetryProperties(retryEnabled,
                        retryEnabled ? 2 : 1, Duration.ofMillis(10),
                        Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(5))),
                new AiContextWindowService(AiContextWindowProperties.defaults()), clock,
                AiRestClientFactory.create(server.baseUrl(), Duration.ofSeconds(1),
                        Duration.ofSeconds(2), 65_536L));
    }

    private static AiChatRequest governed() {
        AiChatRequest old = AiTestFixtures.request();
        return new AiChatRequest(old.userId(), old.organizationId(), old.chatId(),
                old.providerOperationId(), old.systemInstructions(),
                old.developerInstructions(), old.userMessage(), old.history(),
                4_096L, 128);
    }

    private static String success() {
        return """
                {"id":"resp-test","model":"gpt-4.1","status":"completed",
                 "output_text":"ok","usage":{"input_tokens":20,"output_tokens":10}}
                """;
    }
}
