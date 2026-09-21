package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-loopback HTTP boundary contract, without an external provider or Docker. */
@Tag("contract")
@Timeout(10)
class AiRestClientFactoryBoundaryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void declaredLengthGreaterThanLimitFailsBeforeStringCanBeMaterialized()
            throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "x".repeat(65));
            assertThatThrownBy(() -> client(server, 64).get().uri("/large")
                    .retrieve().body(String.class))
                    .hasRootCauseInstanceOf(AiResponseTooLargeIOException.class);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void chunkedResponseWithoutContentLengthIsBoundedDuringStreaming() throws IOException {
        com.sun.net.httpserver.HttpServer chunked = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        chunked.createContext("/chunked", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0); // HTTP chunked; unknown length.
            try (var output = exchange.getResponseBody()) {
                output.write("x".repeat(65).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        chunked.start();
        try {
            String url = "http://127.0.0.1:" + chunked.getAddress().getPort();
            RestClient client = AiRestClientFactory.create(url, TIMEOUT, TIMEOUT, 64);
            assertThatThrownBy(() -> client.get().uri("/chunked")
                    .retrieve().body(String.class))
                    .hasRootCauseInstanceOf(AiResponseTooLargeIOException.class);
        } finally {
            chunked.stop(0);
        }
    }

    @Test
    void exactlyMaximumResponseBytesRemainReadable() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "x".repeat(64));
            assertThat(client(server, 64).get().uri("/exact")
                    .retrieve().body(String.class)).isEqualTo("x".repeat(64));
        }
    }

    @Test
    void oversizedErrorResponseCannotBypassBodyLimit() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(429, "{\"error\":\"" + "x".repeat(128) + "\"}");
            assertThatThrownBy(() -> client(server, 64).get().uri("/error")
                    .retrieve().body(String.class))
                    .hasRootCauseInstanceOf(AiResponseTooLargeIOException.class);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void chunkedErrorResponseCannotBypassBodyLimit() throws IOException {
        com.sun.net.httpserver.HttpServer chunked = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        chunked.createContext("/chunked-error", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, 0); // HTTP chunked; unknown length.
            try (var output = exchange.getResponseBody()) {
                output.write(("{\"error\":\"" + "x".repeat(128) + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        chunked.start();
        try {
            String url = "http://127.0.0.1:" + chunked.getAddress().getPort();
            RestClient client = AiRestClientFactory.create(url, TIMEOUT, TIMEOUT, 64);
            assertThatThrownBy(() -> client.get().uri("/chunked-error")
                    .retrieve().body(String.class))
                    .hasRootCauseInstanceOf(AiResponseTooLargeIOException.class);
        } finally {
            chunked.stop(0);
        }
    }

    @Test
    void boundedErrorResponseRetainsHttpStatusAndBody() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            String error = "{\"error\":\"rate limit\"}";
            server.enqueue(429, error);
            assertThatThrownBy(() -> client(server, 64).get().uri("/rate-limited")
                    .retrieve().body(String.class))
                    .isInstanceOf(HttpClientErrorException.TooManyRequests.class)
                    .satisfies(throwable -> {
                        HttpClientErrorException.TooManyRequests response =
                                (HttpClientErrorException.TooManyRequests) throwable;
                        assertThat(response.getResponseBodyAsString()).isEqualTo(error);
                    });
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void HTTPContractServerRecordsWireRequestAndProviderResponseHeaders() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, Map.of("x-request-id", "provider-generated-id"), "{\"ok\":true}");
            var response = client(server, 1024).post().uri("/wire")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Request-Id", "logical-attempt-id")
                    .body("{\"user\":\"hello\"}")
                    .retrieve().toEntity(String.class);
            assertThat(response.getHeaders().getFirst("x-request-id"))
                    .isEqualTo("provider-generated-id");
            assertThat(server.singleRequest().method()).isEqualTo("POST");
            assertThat(server.singleRequest().path()).isEqualTo("/wire");
            assertThat(server.singleRequest().header("X-Client-Request-Id"))
                    .isEqualTo("logical-attempt-id");
            assertThat(server.singleRequest().header(HttpHeaders.CONTENT_TYPE))
                    .contains("application/json");
            assertThat(server.singleRequest().jsonBody().get("user").asString())
                    .isEqualTo("hello");
        }
    }

    @Test
    void unexpectedSecondRequestGetsExplicit500NotAccidentalSuccess() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            server.enqueue(200, "{\"ok\":true}");
            client(server, 1024).get().uri("/first").retrieve().body(String.class);
            assertThatThrownBy(() -> client(server, 1024).get().uri("/second")
                    .retrieve().body(String.class))
                    .hasMessageContaining("500");
            assertThat(server.requests()).hasSize(2);
        }
    }

    @Test
    void invalidTransportParametersFailBeforeNetwork() throws IOException {
        try (ProviderContractTestServer server = new ProviderContractTestServer()) {
            assertThatThrownBy(() -> AiRestClientFactory.create(
                    server.baseUrl(), Duration.ZERO, TIMEOUT, 64))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AiRestClientFactory.create(
                    server.baseUrl(), TIMEOUT, Duration.ZERO, 64))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AiRestClientFactory.create(
                    server.baseUrl(), TIMEOUT, TIMEOUT, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(server.requests()).isEmpty();
        }
    }

    private static RestClient client(ProviderContractTestServer server, int maxBodyBytes) {
        return AiRestClientFactory.create(server.baseUrl(), TIMEOUT, TIMEOUT, maxBodyBytes);
    }
}
