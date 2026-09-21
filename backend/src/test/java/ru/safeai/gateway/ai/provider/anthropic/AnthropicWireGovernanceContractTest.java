package ru.safeai.gateway.ai.provider.anthropic;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.safeai.gateway.ai.dto.AiChatRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the same real HTTP stack as production without external vendor calls. */
@Tag("contract")
@Timeout(10)
class AnthropicWireGovernanceContractTest {
    private static final String SECRET = "dummy-anthropic-token-do-not-log";

    @Test
    void sendsActualRouteOutputCapAndSeparatesSystemAndDeveloperBlocks()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, Map.of("request-id", "anthropic-request-7"), success());
            var result = provider(server, false).sendMessage(governed());
            var wire = server.singleRequest();
            assertThat(wire.path()).isEqualTo("/messages");
            assertThat(wire.header("x-api-key")).isEqualTo(SECRET);
            assertThat(wire.header("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(wire.jsonBody().get("max_tokens").intValue()).isEqualTo(128);
            assertThat(wire.jsonBody().get("system")).hasSize(2);
            assertThat(wire.jsonBody().get("messages")).hasSize(1);
            assertThat(result.providerRequestId()).isEqualTo("anthropic-request-7");
        }
    }

    @Test
    void overloaded529MayHaveReachedProviderAndIsNotBlindlyRetried()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
            assertThatThrownBy(() -> provider(server, true).sendMessage(governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(throwable -> assertThat(ProviderFailureCertainty.classify(
                            (AiProviderException) throwable)).isEqualTo(OutcomeCertainty.AMBIGUOUS));
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void malformedHttp200CannotBeRetriedAsIfNoExecutionHappened()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "{broken");
            assertThatThrownBy(() -> provider(server, true).sendMessage(governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(throwable -> assertThat(ProviderFailureCertainty.classify(
                            (AiProviderException) throwable)).isEqualTo(OutcomeCertainty.AMBIGUOUS));
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void secretsAndRawProviderBodyNeverAppearInExceptionMessage()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(401, "{\"type\":\"error\",\"error\":{\"message\":\"" + SECRET + " private text\"}}");
            assertThatThrownBy(() -> provider(server, false).sendMessage(governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(throwable -> {
                        AiProviderException error = (AiProviderException) throwable;
                        assertThat(error.getMessage()).doesNotContain(SECRET, "private text");
                        assertThat(error.getStatusCode()).isEqualTo(401);
                    });
            assertThat(server.requests()).hasSize(1);
        }
    }

    private static AnthropicProvider provider(ProviderContractTestServer server,
                                              boolean retryEnabled) {
        AnthropicProperties props = new AnthropicProperties("https://api.anthropic.com/v1",
                SECRET, "claude-sonnet", "2023-06-01", 64_000, 2_048,
                100_000, 65_536L, Duration.ofSeconds(1), Duration.ofSeconds(2));
        Clock clock = Clock.fixed(AiTestFixtures.NOW, ZoneOffset.UTC);
        ModelPricingService prices = new ModelPricingService(
                new ModelPricingProperties(List.of()), clock);
        return new AnthropicProvider(props,
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
                {"id":"msg-test","model":"claude-sonnet","type":"message",
                 "role":"assistant","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":20,"output_tokens":10}}
                """;
    }
}
