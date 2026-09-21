package ru.safeai.gateway.ai.provider.openai;

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
import ru.safeai.gateway.ai.provider.AiContextWindowProperties;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.AiRetryProperties;
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

/** Actual HTTP payloads: missing physical model is not manufactured from request.model. */
@Tag("contract")
@Timeout(10)
class OpenAiResolvedModelProvenanceContractTest {

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
    void successfulHttpWithoutPhysicalModelIsAmbiguousAndCannotBeRetried() {
        server.enqueue(200, """
                {"id":"resp_123","status":"completed","output_text":"answer",
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """);

        // Would receive this only if an unsafe blind retry were attempted.
        server.enqueue(200, """
                {"id":"resp_456","model":"gpt-4.1","status":"completed",
                 "output_text":"second","usage":{"input_tokens":1,"output_tokens":1}}
                """);

        assertThatThrownBy(() -> provider().sendMessage(request()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> {
                    AiProviderException providerError = (AiProviderException) error;
                    assertThat(providerError.getErrorType())
                            .isEqualTo(AiProviderErrorType.PROTOCOL_ERROR);
                    assertThat(providerError.isOutcomeAmbiguous()).isTrue();
                    assertThat(providerError.isRetryable()).isFalse();
                });

        assertThat(server.requests()).hasSize(1);
    }

    private OpenAiProvider provider() {
        OpenAiProperties properties = new OpenAiProperties(
                "https://api.openai.com/v1", "test-secret", "gpt-4.1",
                64_000, 2_048, 100_000, 2L * 1024L * 1024L,
                null, Duration.ofSeconds(1), Duration.ofSeconds(2)
        );

        // AiRetryProperties requires both backoffs >= 10 ms.
        AiRetryProperties retry = new AiRetryProperties(
                true, 2,
                Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(5)
        );

        RestClient client = AiRestClientFactory.create(
                server.baseUrl(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                2L * 1024L * 1024L
        );

        return new OpenAiProvider(
                properties,
                new AiResponseMetadataService(
                        new StaticConfigurationPricingResolver(
                                new ModelPricingService(
                                        new ModelPricingProperties(List.of()),
                                        Clock.fixed(NOW, ZoneOffset.UTC)
                                )
                        )
                ),
                new AiProviderRetryExecutor(retry),
                new AiContextWindowService(AiContextWindowProperties.defaults()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                client
        );
    }
}
