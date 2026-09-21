package ru.safeai.gateway.ai.provider.anthropic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.pricing.StaticConfigurationPricingResolver;
import ru.safeai.gateway.ai.provider.*;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.NOW;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.request;

@Tag("contract")
@Timeout(10)
class AnthropicResolvedModelProvenanceContractTest {
    private ProviderContractTestServer server;

    @BeforeEach void setUp() throws IOException { server = new ProviderContractTestServer(); }
    @AfterEach void tearDown() { server.close(); }

    @Test
    void successfulHttpWithoutPhysicalModelIsAmbiguousAndCannotBeRetried() {
        server.enqueue(200, """
                {"id":"msg_123","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"answer"}],
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """);
        server.enqueue(200, """
                {"id":"msg_456","model":"claude-sonnet","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"second"}],
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """);
        assertThatThrownBy(() -> provider().sendMessage(request()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> {
                    AiProviderException providerError = (AiProviderException) error;
                    assertThat(providerError.getErrorType()).isEqualTo(AiProviderErrorType.PROTOCOL_ERROR);
                    assertThat(providerError.isOutcomeAmbiguous()).isTrue();
                    assertThat(providerError.isRetryable()).isFalse();
                });
        assertThat(server.requests()).hasSize(1);
    }

    private AnthropicProvider provider() {
        AnthropicProperties properties = new AnthropicProperties(
                "https://api.anthropic.com/v1", "test-secret", "claude-sonnet",
                "2023-06-01", 64_000, 2_048, 100_000,
                2L * 1024L * 1024L, Duration.ofSeconds(1), Duration.ofSeconds(2));
        AiRetryProperties retry = new AiRetryProperties(true, 2,
                Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(5));
        RestClient client = AiRestClientFactory.create(
                server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2), 2L * 1024L * 1024L);
        return new AnthropicProvider(properties, new AiResponseMetadataService(
                new StaticConfigurationPricingResolver(new ModelPricingService(
                        new ModelPricingProperties(List.of()), Clock.fixed(NOW, ZoneOffset.UTC)))),
                new AiProviderRetryExecutor(retry),
                new AiContextWindowService(AiContextWindowProperties.defaults()),
                Clock.fixed(NOW, ZoneOffset.UTC), client);
    }
}
