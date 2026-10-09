package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthCookieServiceTest {
    private static final String ACCESS_NAME = "safeai_access";
    private static final String REFRESH_NAME = "safeai_refresh";

    @Mock private AuthCookieProperties properties;
    private AuthCookieService service;

    @BeforeEach
    void setUp() { service = new AuthCookieService(properties); }

    @Test
    void addAccessTokenCookieUsesProductionAttributes() {
        stubAccessTokenCookieProperties();
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.addAccessTokenCookie(response, "access-token");
        assertThat(onlySetCookie(response))
                .contains(ACCESS_NAME + "=access-token", "Path=/", "Max-Age=900",
                        "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure", "Domain=");
    }

    @Test
    void addRefreshTokenCookieUsesRestrictedPathAndProvidedMaxAge() {
        stubRefreshTokenCookieProperties();
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.addRefreshTokenCookie(response, "refresh-token", Duration.ofDays(7));
        assertThat(onlySetCookie(response))
                .contains(REFRESH_NAME + "=refresh-token", "Path=/api/auth",
                        "Max-Age=604800", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=");
    }

    @Test
    void clearAuthCookiesExpiresEveryCurrentAndLegacyIdentityWithoutTouchingCsrf() {
        stubAuthCookieNames();
        stubDefaultCookieAttributes();
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.clearAuthCookies(response);
        List<String> headers = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));

        // Every distinct historical identity must be expired. Requiring exactly
        // five deletions would permit existing legacy sessions to survive logout.
        assertThat(headers).hasSizeGreaterThanOrEqualTo(10);
        assertThat(headers).allSatisfy(header -> assertThat(header)
                .contains("Max-Age=0", "HttpOnly")
                .doesNotContain("XSRF-TOKEN"));

        assertExpired(headers, ACCESS_NAME, "/");
        assertExpired(headers, REFRESH_NAME, "/api/auth");
        assertExpired(headers, REFRESH_NAME, "/");
        assertExpired(headers, "safeai-access", "/");
        assertExpired(headers, "safeai-refresh", "/");
        assertExpired(headers, "safeai-refresh", "/api/auth");
        assertExpired(headers, "access_token", "/");
        assertExpired(headers, "refresh_token", "/");
        assertExpired(headers, "refresh_token", "/api/auth");
        assertExpired(headers, "__Secure-safeai-refresh", "/");
        assertExpired(headers, "__Secure-safeai-refresh", "/api/auth");
        assertExpired(headers, "__Host-safeai-access", "/");

        assertThat(headers).filteredOn(h -> h.startsWith("__Secure-")
                || h.startsWith("__Host-"))
                .allSatisfy(h -> assertThat(h).contains("Secure"));
    }

    @Test
    void extractRefreshTokenReadsConfiguredCookieName() {
        when(properties.refreshTokenName()).thenReturn(REFRESH_NAME);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(ACCESS_NAME, "access-token"),
                new Cookie(REFRESH_NAME, "refresh-token"));
        assertThat(service.extractRefreshToken(request)).isEqualTo("refresh-token");
    }

    @Test
    void extractRefreshTokenReturnsNullForMissingOrBlankCookie() {
        when(properties.refreshTokenName()).thenReturn(REFRESH_NAME);
        MockHttpServletRequest missing = new MockHttpServletRequest();
        missing.setCookies(new Cookie(ACCESS_NAME, "access-token"));
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setCookies(new Cookie(REFRESH_NAME, " "));
        assertThat(service.extractRefreshToken(missing)).isNull();
        assertThat(service.extractRefreshToken(blank)).isNull();
    }

    @Test
    void cookieCreationRejectsBlankTokenAndNonPositiveMaxAge() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.addAccessTokenCookie(response, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addRefreshTokenCookie(
                response, "refresh-token", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void cookiesUseSecureAndDomainWhenConfigured() {
        when(properties.accessTokenName()).thenReturn(ACCESS_NAME);
        when(properties.accessTokenMaxAge()).thenReturn(Duration.ofMinutes(15));
        when(properties.secure()).thenReturn(true);
        when(properties.sameSite()).thenReturn("Lax");
        when(properties.hasDomain()).thenReturn(true);
        when(properties.domain()).thenReturn("example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.addAccessTokenCookie(response, "access-token");
        assertThat(onlySetCookie(response)).contains("Secure", "Domain=example.com");
    }

    @Test
    void securePrefixesCannotBeIssuedWithInvalidAttributes() {
        when(properties.accessTokenName()).thenReturn("__Host-safeai-access");
        when(properties.accessTokenMaxAge()).thenReturn(Duration.ofMinutes(15));
        when(properties.secure()).thenReturn(true);
        when(properties.hasDomain()).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.addAccessTokenCookie(response, "token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("__Host-");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    private static void assertExpired(List<String> headers, String name, String path) {
        assertThat(headers).anySatisfy(header -> assertThat(header)
                .startsWith(name + "=")
                .contains("Path=" + path + ";", "Max-Age=0"));
    }

    private void stubAccessTokenCookieProperties() {
        when(properties.accessTokenName()).thenReturn(ACCESS_NAME);
        when(properties.accessTokenMaxAge()).thenReturn(Duration.ofMinutes(15));
        stubDefaultCookieAttributes();
    }

    private void stubRefreshTokenCookieProperties() {
        when(properties.refreshTokenName()).thenReturn(REFRESH_NAME);
        stubDefaultCookieAttributes();
    }

    private void stubAuthCookieNames() {
        when(properties.accessTokenName()).thenReturn(ACCESS_NAME);
        when(properties.refreshTokenName()).thenReturn(REFRESH_NAME);
    }

    private void stubDefaultCookieAttributes() {
        when(properties.secure()).thenReturn(false);
        when(properties.sameSite()).thenReturn("Lax");
        when(properties.hasDomain()).thenReturn(false);
    }

    private String onlySetCookie(MockHttpServletResponse response) {
        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookies).hasSize(1);
        return cookies.getFirst();
    }
}
