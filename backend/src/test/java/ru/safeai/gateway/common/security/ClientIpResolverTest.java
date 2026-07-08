package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void resolve_shouldIgnoreForwardedHeadersWhenRemoteAddressIsNotTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of())
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void resolve_shouldUseXForwardedForWhenRemoteAddressIsTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of("127.0.0.1/32"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.10, 127.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void resolve_shouldUseNearestUntrustedAddressFromRightToLeft() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of("127.0.0.1/32"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        request.addHeader(
                "X-Forwarded-For",
                "1.2.3.4, 198.51.100.10, 127.0.0.1"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void resolve_shouldUseXRealIpWhenXForwardedForIsMissingAndRemoteAddressIsTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of("127.0.0.1/32"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "198.51.100.20");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void resolve_shouldFallbackToRemoteAddressWhenForwardedHeadersAreInvalid() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of("127.0.0.1/32"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "not-an-ip");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolve_shouldStripIpv4Port() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of())
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10:54321");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void resolve_shouldReturnUnknownWhenRemoteAddressIsMissing() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of())
        );

        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn(null);

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }

    @Test
    void resolve_shouldIgnoreXRealIpWhenRemoteAddressIsNotTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of())
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Real-IP", "198.51.100.20");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void resolve_shouldIgnoreUnknownValueInXForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of("127.0.0.1/32"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "unknown");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolve_shouldIgnoreBlankXForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of("127.0.0.1/32"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolve_shouldUseNearestUntrustedAddressWhenMultipleTrustedProxiesExist() {
        ClientIpResolver resolver = new ClientIpResolver(
                new ClientIpProperties(List.of(
                        "127.0.0.1/32",
                        "10.0.0.0/8"
                ))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(
                "X-Forwarded-For",
                "198.51.100.10, 10.0.0.5, 127.0.0.1"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }
}