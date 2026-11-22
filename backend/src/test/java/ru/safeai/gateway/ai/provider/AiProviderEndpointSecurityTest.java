package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.safeai.gateway.ai.testsupport.ProviderContractTestServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
@Timeout(15)
class AiProviderEndpointSecurityTest {
    @Test
    void rejectsEncodedTraversalAndMalformedUrlWithoutRetainingRawValue() {
        for (String url : Set.of("https://api.openai.com/%76%31", "https://api.openai.com/x/../v1",
                "https://api.openai.com/v1?key=fixture-secret", "https://fixture-secret@api.openai.com/v1",
                "https://api.openai.com/v1/fixture-secret[", "https://api.openai.com:0443/v1")) {
            assertThatThrownBy(() -> ProviderPropertyValidator.requireAllowedHttpsBaseUrl(
                    url, "provider-url", Set.of("https://api.openai.com/v1")))
                    .isInstanceOf(IllegalStateException.class).hasNoCause()
                    .hasMessageNotContaining("fixture-secret");
        }
    }

    @Test
    void absoluteUriAndCustomHostCannotEscapePinnedEndpoint() throws Exception {
        try (var expected = new ProviderContractTestServer();
             var attacker = new ProviderContractTestServer()) {
            expected.enqueue(200, "ok");
            attacker.enqueue(200, "unexpected");
            var client = AiRestClientFactory.create(expected.baseUrl() + "/v1",
                    Duration.ofSeconds(1), Duration.ofSeconds(2));
            assertThatThrownBy(() -> client.get().uri(URI.create(attacker.baseUrl() + "/v1/escape"))
                    .retrieve().body(String.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.get().uri(URI.create(expected.baseUrl() + "/other"))
                    .retrieve().body(String.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.get().uri("/ok").header("Host", "attacker.invalid")
                    .retrieve().body(String.class)).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThat(expected.requests()).isEmpty();
            assertThat(attacker.requests()).isEmpty();
            assertThat(client.get().uri("/ok").retrieve().body(String.class)).isEqualTo("ok");
            assertThat(expected.singleRequest().path()).isEqualTo("/v1/ok");
        }
    }

    @Test
    void blockedAddressClassesAndUnresolvedSocketsCannotConnect() throws Exception {
        for (String host : Set.of("0.0.0.0", "127.0.0.1", "10.3.4.5", "100.64.0.1",
                "169.254.169.254", "172.16.0.1", "192.168.0.1", "192.0.2.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "::1", "::ffff:127.0.0.1",
                "fc00::1", "fe80::1", "2001:db8::1", "2002::1", "64:ff9b::1")) {
            assertThat(AiProviderEndpoint.isPublicAddress(new InetSocketAddress(InetAddress.getByName(host), 443)))
                    .as("address %s", host).isFalse();
        }
        assertThat(AiProviderEndpoint.isPublicAddress(InetSocketAddress.createUnresolved("example.test", 443)))
                .isFalse();
        for (String host : Set.of("8.8.8.8", "2606:4700::1111")) {
            assertThat(AiProviderEndpoint.isPublicAddress(new InetSocketAddress(InetAddress.getByName(host), 443)))
                    .isTrue();
        }
    }

    @Test
    void httpsLoopbackIsRejectedBeforeTcpConnectionNotJustBeforeHttp() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(350);
            var client = AiRestClientFactory.create("https://127.0.0.1:" + listener.getLocalPort(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1));
            assertThatThrownBy(() -> client.get().uri("/v1").retrieve().body(String.class))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThatThrownBy(() -> {
                try (var ignored = listener.accept()) {
                    throw new AssertionError("Prohibited TCP connection reached the listener");
                }
            }).isInstanceOf(SocketTimeoutException.class);
        }
    }
}
