package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void resolveIgnoresForwardedHeadersWhenRemoteAddressIsNotTrustedProxy() {
        ClientIpResolver resolver =
                new ClientIpResolver(
                        new ClientIpProperties(
                                List.of()
                        )
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "203.0.113.10"
        );

        request.addHeader(
                "X-Forwarded-For",
                "1.2.3.4"
        );

        request.addHeader(
                "X-Real-IP",
                "5.6.7.8"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "203.0.113.10"
        );
    }

    @Test
    void resolveUsesXForwardedForWhenRemoteAddressIsTrustedProxy() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "198.51.100.10, 127.0.0.1"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "198.51.100.10"
        );
    }

    @Test
    void resolveUsesNearestUntrustedAddressFromRightToLeft() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "1.2.3.4, 198.51.100.10, 127.0.0.1"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "198.51.100.10"
        );
    }

    @Test
    void resolveReturnsRealClientWhenAllControlledProxiesAreTrusted() {
        ClientIpResolver resolver =
                new ClientIpResolver(
                        new ClientIpProperties(
                                List.of(
                                        "172.28.0.0/24",
                                        "198.51.100.0/24"
                                )
                        )
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "172.28.0.10"
        );

        request.addHeader(
                "X-Forwarded-For",
                "203.0.113.10, 198.51.100.20"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "203.0.113.10"
        );
    }

    @Test
    void resolveUsesXRealIpWhenXForwardedForIsMissingAndRemoteAddressIsTrustedProxy() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Real-IP",
                "198.51.100.20"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "198.51.100.20"
        );
    }

    @Test
    void resolveFallsBackToRemoteAddressWhenForwardedHeadersAreInvalid() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "not-an-ip"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "127.0.0.1"
        );
    }

    @Test
    void resolveRejectsHostnameWithoutDnsLookup() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "attacker.example.com"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "127.0.0.1"
        );
    }

    @Test
    void resolveRejectsHexLookingHostnameWithoutDnsLookup() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "dead.beef"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "127.0.0.1"
        );
    }

    @Test
    void resolveStripsIpv4Port() {
        ClientIpResolver resolver =
                new ClientIpResolver(
                        new ClientIpProperties(
                                List.of()
                        )
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "203.0.113.10:54321"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "203.0.113.10"
        );
    }

    @Test
    void resolveSupportsBracketedIpv6WithPort() {
        ClientIpResolver resolver =
                resolver(
                        "2001:db8:ffff::/48"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "2001:db8:ffff::1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "[2001:db8::1]:12345"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "2001:db8:0:0:0:0:0:1"
        );
    }

    @Test
    void resolveReturnsUnknownWhenRemoteAddressIsMissing() {
        ClientIpResolver resolver =
                new ClientIpResolver(
                        new ClientIpProperties(
                                List.of()
                        )
                );

        HttpServletRequest request =
                mock(
                        HttpServletRequest.class
                );

        when(
                request.getRemoteAddr()
        ).thenReturn(null);

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "unknown"
        );
    }

    @Test
    void resolveIgnoresXRealIpWhenRemoteAddressIsNotTrustedProxy() {
        ClientIpResolver resolver =
                new ClientIpResolver(
                        new ClientIpProperties(
                                List.of()
                        )
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "203.0.113.10"
        );

        request.addHeader(
                "X-Real-IP",
                "198.51.100.20"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "203.0.113.10"
        );
    }

    @Test
    void resolveIgnoresUnknownValueInXForwardedFor() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "unknown"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "127.0.0.1"
        );
    }

    @Test
    void resolveIgnoresBlankXForwardedFor() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "   "
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "127.0.0.1"
        );
    }

    @Test
    void resolveIgnoresForwardedHeaderWithMoreThan32Hops() {
        ClientIpResolver resolver =
                resolver(
                        "172.28.0.0/24"
                );

        String xff =
                IntStream.range(
                                0,
                                33
                        )
                        .mapToObj(index ->
                                "192.0.2."
                                        + (
                                        (index % 200)
                                                + 1
                                )
                        )
                        .collect(
                                Collectors.joining(
                                        ","
                                )
                        );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "172.28.0.10"
        );

        request.addHeader(
                "X-Forwarded-For",
                xff
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "172.28.0.10"
        );
    }

    @Test
    void resolveRejectsEntireChainWhenOneHopIsInvalid() {
        ClientIpResolver resolver =
                resolver(
                        "127.0.0.1/32"
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr(
                "127.0.0.1"
        );

        request.addHeader(
                "X-Forwarded-For",
                "198.51.100.10, attacker.example.com, 127.0.0.1"
        );

        assertThat(
                resolver.resolve(request)
        ).isEqualTo(
                "127.0.0.1"
        );
    }

    private ClientIpResolver resolver(
            String cidr
    ) {
        return new ClientIpResolver(
                new ClientIpProperties(
                        List.of(cidr)
                )
        );
    }
}
