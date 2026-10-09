package ru.safeai.gateway.ai.provider.openai;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.dto.*;
import ru.safeai.gateway.ai.execution.*;
import ru.safeai.gateway.ai.exception.*;
import ru.safeai.gateway.ai.pricing.*;
import ru.safeai.gateway.ai.provider.*;
import ru.safeai.gateway.ai.testsupport.*;
import java.io.IOException;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.*;

@Tag("contract") @Timeout(10)
class OpenAiResolvedModelProvenanceContractTest {
    private ProviderContractTestServer server;
    private final AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
    @BeforeEach void setUp() throws IOException { server = new ProviderContractTestServer(); }
    @AfterEach void tearDown() { server.close(); }
    @Test void successfulHttpWithoutPhysicalModelIsAmbiguousAndCannotBeRetried() {
        server.enqueue(200, """
                {"id":"resp_123","status":"completed","output_text":"answer","usage":{"input_tokens":10,"output_tokens":2}}
                """);
        server.enqueue(200, "{\"id\":\"second\",\"model\":\"gpt-4.1\"}");
        AiChatRequest input = request();
        assertThatThrownBy(() -> scope.execute(input.providerOperationId(),
                mock(AiExecutionAttemptObserver.class), () -> provider().sendMessage(input)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(e -> {
                    AiProviderException ex = (AiProviderException) e;
                    assertThat(ex.getErrorType()).isEqualTo(AiProviderErrorType.PROTOCOL_ERROR);
                    assertThat(ex.isOutcomeAmbiguous()).isTrue();
                    assertThat(ex.isRetryable()).isFalse();
                });
        assertThat(server.requests()).hasSize(1);
    }
    private OpenAiProvider provider() {
        OpenAiProperties properties = new OpenAiProperties("https://api.openai.com/v1", "test-secret", "gpt-4.1", 64_000, 2_048, 100_000, 2L * 1024 * 1024, false, Duration.ofSeconds(1), Duration.ofSeconds(2));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AiRetryProperties retry = new AiRetryProperties(true, 2, Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(5));
        RestClient client = AiRestClientFactory.create(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2), 2L * 1024 * 1024);
        return new OpenAiProvider(properties,
                new AiResponseMetadataService(new StaticConfigurationPricingResolver(
                        new ModelPricingService(new ModelPricingProperties(List.of()), clock))),
                new AiProviderRetryExecutor(retry, scope),
                new AiContextWindowService(AiContextWindowProperties.defaults()), clock, client);
    }
}
