package ru.safeai.gateway.ai.provider.anthropic;
import org.junit.jupiter.api.*;
import ru.safeai.gateway.ai.dto.*;
import ru.safeai.gateway.ai.exception.*;
import ru.safeai.gateway.ai.execution.*;
import ru.safeai.gateway.ai.pricing.*;
import ru.safeai.gateway.ai.provider.*;
import ru.safeai.gateway.ai.testsupport.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Tag("contract") @Timeout(10)
class AnthropicWireGovernanceContractTest {
    private static final String SECRET = "dummy-anthropic-token-do-not-log";
    private final AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
    private AiChatResponse send(AnthropicProvider provider, AiChatRequest input) {
        return scope.execute(input.providerOperationId(), mock(AiExecutionAttemptObserver.class),
                () -> provider.sendMessage(input));
    }
    private AiChatRequest governed() {
        AiChatRequest old = AiTestFixtures.request();
        return new AiChatRequest(old.userId(), old.organizationId(), old.chatId(),
                old.providerOperationId(), old.systemInstructions(), old.developerInstructions(),
                old.userMessage(), old.history(), 4_096L, 128);
    }
    private AnthropicProvider provider(ProviderContractTestServer server, boolean retryEnabled) {
        AnthropicProperties props = new AnthropicProperties("https://api.anthropic.com/v1", SECRET, "claude-sonnet", "2023-06-01", 64_000, 2_048, 100_000, 65_536L, Duration.ofSeconds(1), Duration.ofSeconds(2));
        Clock clock = Clock.fixed(AiTestFixtures.NOW, ZoneOffset.UTC);
        AiRetryProperties retry = new AiRetryProperties(retryEnabled, retryEnabled ? 2 : 1,
                Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(5));
        return new AnthropicProvider(props,
                new AiResponseMetadataService(new StaticConfigurationPricingResolver(
                        new ModelPricingService(new ModelPricingProperties(List.of()), clock))),
                new AiProviderRetryExecutor(retry, scope),
                new AiContextWindowService(AiContextWindowProperties.defaults()), clock,
                AiRestClientFactory.create(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2), 65_536L));
    }
    private static String success() { return """
        {"id":"msg-test","model":"claude-sonnet","type":"message","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":20,"output_tokens":10}}
        """; }
    @Test void sendsActualRouteOutputCapAndSeparatesSystemAndDeveloperBlocks() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, Map.of("request-id", "anthropic-request-7"), success());
            AiChatResponse result = send(provider(server, false), governed());
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
    @Test void overloaded529MayHaveReachedProviderAndIsNotBlindlyRetried() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
            assertAmbiguous(() -> send(provider(server, true), governed()));
            assertThat(server.requests()).hasSize(1);
        }
    }
    @Test void malformedHttp200CannotBeRetriedAsIfNoExecutionHappened() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "{broken");
            assertAmbiguous(() -> send(provider(server, true), governed()));
            assertThat(server.requests()).hasSize(1);
        }
    }
    @Test void secretsAndRawProviderBodyNeverAppearInExceptionMessage() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(401, "{\"type\":\"error\",\"error\":{\"message\":\"" + SECRET + " private text\"}}");
            assertThatThrownBy(() -> send(provider(server, false), governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(e -> {
                        AiProviderException error = (AiProviderException) e;
                        assertThat(error.getMessage()).doesNotContain(SECRET, "private text");
                        assertThat(error.getStatusCode()).isEqualTo(401);
                    });
            assertThat(server.requests()).hasSize(1);
        }
    }
    private static void assertAmbiguous(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(AiProviderException.class)
                .satisfies(e -> assertThat(ProviderFailureCertainty.classify((AiProviderException)e))
                        .isEqualTo(OutcomeCertainty.AMBIGUOUS));
    }
}
