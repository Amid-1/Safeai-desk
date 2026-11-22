package ru.safeai.gateway.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
@Timeout(15)
class AiProviderTransportHardeningTest {
    @Test
    void redirectWithSuccessfulLookingBodyIsRejectedWithoutSecondRequest() throws IOException {
        try (var server = new ProviderContractTestServer()) {
            server.enqueue(307, Map.of("Location", server.baseUrl() + "/other"), "{\"ok\":true}");
            server.enqueue(200, "{\"ok\":true}");
            assertThatThrownBy(() -> post(client(server.baseUrl()), "/original"))
                .isInstanceOf(ResourceAccessException.class);
            assertThat(server.requests()).hasSize(1);
            assertThat(server.singleRequest().path()).isEqualTo("/original");
        }
    }

    @Test
    void compressedBodyIsRejectedEvenWhenSmall() throws IOException {
        try (var server = new ProviderContractTestServer()) {
            server.enqueue(200, Map.of("Content-Encoding", "gzip"), "{\"ok\":true}");
            assertThatThrownBy(() -> post(client(server.baseUrl()), "/original"))
                .isInstanceOf(ResourceAccessException.class);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void disconnectAfterPostDoesNotReplayAtTransportLayer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/disconnect", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                calls.incrementAndGet();
                // Connection closes before a response is written.
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> post(client(url(server)), "/disconnect"))
                .isInstanceOf(ResourceAccessException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteSuccessfulBodyCannotOutliveInheritedDeadline() throws IOException {
        assertStalledResponseTimesOut(200, true, false);
    }

    @Test
    void incompleteRateLimitBodyCannotBecomeKnownRejection() throws IOException {
        assertStalledResponseTimesOut(429, true, false);
    }

    @Test
    void delayedHeadersCannotOutliveInheritedDeadline() throws IOException {
        assertStalledResponseTimesOut(200, false, false);
    }

    @Test
    void periodicBytesCannotExtendAbsoluteDeadline() throws IOException {
        assertStalledResponseTimesOut(200, true, true);
    }

    private static void assertStalledResponseTimesOut(int status, boolean headers, boolean drip)
        throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        server.createContext("/warmup", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'o', 'k'});
            }
        });
        server.createContext("/stall", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                calls.incrementAndGet();
                if (headers) {
                    exchange.sendResponseHeaders(status, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                }
                if (drip) {
                    // Finite, interruptible fixture; each chunk arrives well before the idle timeout.
                    for (int i = 0; i < 40 && !release.await(200, TimeUnit.MILLISECONDS); i++) {
                        exchange.getResponseBody().write(' ');
                        exchange.getResponseBody().flush();
                    }
                } else {
                    if (!release.await(8, TimeUnit.SECONDS)) {
                        throw new IOException("Test fixture was not released in time");
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            RestClient client = client(url(server));
            // Warm connection and event loops before measuring the inherited bound.
            assertThat(client.get().uri("/warmup").retrieve().body(String.class)).isEqualTo("ok");
            long start = System.nanoTime();
            assertThatThrownBy(() -> AiProviderDispatchDeadline.within(
                start + Duration.ofSeconds(1).toNanos(), () -> post(client, "/stall")))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void expiredDeadlineDoesNotSendAndIsRemovedAfterFailure() throws IOException {
        try (var server = new ProviderContractTestServer()) {
            server.enqueue(200, "ok");
            RestClient client = client(server.baseUrl());
            assertThatThrownBy(() -> AiProviderDispatchDeadline.within(
                System.nanoTime() - 1, () -> post(client, "/expired")))
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThat(server.requests()).isEmpty();
            assertThat(post(client, "/next")).isEqualTo("ok");
            assertThat(server.requests()).hasSize(1);
        }
    }

    private static RestClient client(String url) {
        return AiRestClientFactory.create(url, Duration.ofSeconds(2), Duration.ofSeconds(5), 1024);
    }

    private static String post(RestClient client, String path) {
        return client.post().uri(path).body("{\"input\":\"test\"}").retrieve().body(String.class);
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
