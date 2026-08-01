package ru.safeai.gateway.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.provider.AiResponseTooLargeIOException;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Timeout(10)
class AiRestClientFactoryTest {

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
    void rawResponseBodyIsLimitedBeforeFullMaterialization() {
        server.enqueue(200, "{\"payload\":\"" + "x".repeat(256) + "\"}");

        RestClient client = AiRestClientFactory.create(
                server.baseUrl(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                64
        );

        assertThatThrownBy(() -> client.get()
                .uri("/large")
                .retrieve()
                .body(String.class))
                .hasRootCauseInstanceOf(
                        AiResponseTooLargeIOException.class
                );
    }

    @Test
    void bodyBelowLimitIsReturned() {
        server.enqueue(200, "{\"ok\":true}");

        RestClient client = AiRestClientFactory.create(
                server.baseUrl(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                1_024
        );

        String body = client.get()
                .uri("/small")
                .retrieve()
                .body(String.class);

        assertThat(body).isEqualTo("{\"ok\":true}");
    }
}
