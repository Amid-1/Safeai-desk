package ru.safeai.gateway.ai.provider.openai;
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
class OpenAiWireGovernanceContractTest {
    private static final String SECRET = "dummy-openai-token-do-not-log";
    private final AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
    private AiChatResponse send(OpenAiProvider provider, AiChatRequest input) {
        return scope.execute(input.providerOperationId(), mock(AiExecutionAttemptObserver.class),
                () -> provider.sendMessage(input));
    }
    private AiChatRequest governed() {
        AiChatRequest old = AiTestFixtures.request();
        return new AiChatRequest(old.userId(), old.organizationId(), old.chatId(),
                old.providerOperationId(), old.systemInstructions(), old.developerInstructions(),
                old.userMessage(), old.history(), 4_096L, 128);
    }
    private OpenAiProvider provider(ProviderContractTestServer server, boolean retryEnabled) {
        OpenAiProperties props = new OpenAiProperties("https://api.openai.com/v1", SECRET, "gpt-4.1", 64_000, 2_048, 100_000, 65_536L, false, Duration.ofSeconds(1), Duration.ofSeconds(2));
        Clock clock = Clock.fixed(AiTestFixtures.NOW, ZoneOffset.UTC);
        AiRetryProperties retry = new AiRetryProperties(retryEnabled, retryEnabled ? 2 : 1,
                Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(5));
        return new OpenAiProvider(props,
                new AiResponseMetadataService(new StaticConfigurationPricingResolver(
                        new ModelPricingService(new ModelPricingProperties(List.of()), clock))),
                new AiProviderRetryExecutor(retry, scope),
                new AiContextWindowService(AiContextWindowProperties.defaults()), clock,
                AiRestClientFactory.create(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2), 65_536L));
    }
    private static String success() { return """
        {"id":"resp-test","model":"gpt-4.1","status":"completed","output_text":"ok","usage":{"input_tokens":20,"output_tokens":10}}
        """; }
    @Test void sendsOperationSpecificPhysicalRequestIdAndTheRouteBoundOutputLimit() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, Map.of("x-request-id", "provider-request-7"), success());
            AiChatResponse result = send(provider(server, false), governed());
            var wire = server.singleRequest();
            assertThat(wire.path()).isEqualTo("/responses");
            assertThat(wire.header("Authorization")).isEqualTo("Bearer " + SECRET);
            String clientId = Objects.requireNonNull(wire.header("X-Client-Request-Id"));
            assertThat(UUID.fromString(clientId)).isNotEqualTo(AiTestFixtures.OPERATION_ID);
            assertThat(wire.jsonBody().get("max_output_tokens").intValue()).isEqualTo(128);
            assertThat(wire.jsonBody().get("store").booleanValue()).isFalse();
            assertThat(result.providerRequestId()).isEqualTo("provider-request-7");
        }
    }
    @Test void exactlyOneRetryAfterExplicit429UsesDifferentPhysicalId() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(429, Map.of("Retry-After", "0"),
                    "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}");
            server.enqueue(200, success());
            assertThat(send(provider(server, true), governed()).content()).isEqualTo("ok");
            assertThat(server.requests()).hasSize(2);
            assertThat(server.requests().get(0).header("X-Client-Request-Id"))
                    .isNotEqualTo(server.requests().get(1).header("X-Client-Request-Id"));
            assertThat(server.requests().get(0).jsonBody().get("model").asString())
                    .isEqualTo(server.requests().get(1).jsonBody().get("model").asString());
        }
    }
    @Test void http503IsAmbiguousAndCannotBeBlindlyRetried() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(503, Map.of("x-request-id", "provider-503"),
                    "{\"error\":{\"type\":\"server_error\",\"message\":\"temporary\"}}");
            assertThatThrownBy(() -> send(provider(server, true), governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(e -> {
                        AiProviderException error = (AiProviderException)e;
                        assertThat(error.getStatusCode()).isEqualTo(503);
                        assertThat(ProviderFailureCertainty.classify(error)).isEqualTo(OutcomeCertainty.AMBIGUOUS);
                    });
            assertThat(server.requests()).hasSize(1);
        }
    }
    @Test void malformedHttp200DoesNotGetTurnedIntoSuccessfulAnswerOrBlindRetry() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "not valid json");
            assertAmbiguous(() -> send(provider(server, true), governed()));
            assertThat(server.requests()).hasSize(1);
        }
    }
    @Test void providerErrorBodyAndCredentialsAreNotCopiedIntoPublicExceptionMessage() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(401, "{\"error\":{\"message\":\"" + SECRET + " sensitive body\"}}");
            assertThatThrownBy(() -> send(provider(server, false), governed()))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(e -> {
                        AiProviderException error = (AiProviderException)e;
                        assertThat(error.getMessage()).doesNotContain(SECRET, "sensitive body");
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
