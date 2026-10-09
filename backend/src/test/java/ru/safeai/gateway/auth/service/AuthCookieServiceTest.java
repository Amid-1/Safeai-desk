package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

class AuthCookieServiceTest {

    @Test
    void issuesHttpOnlyAccessAndPathRestrictedRefresh() {
        AuthCookieProperties settings = settings(false, "Lax", null,
                "safeai-access", "safeai-refresh");
        when(settings.accessTokenMaxAge()).thenReturn(Duration.ofMinutes(15));
        AuthCookieService service = new AuthCookieService(settings);
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.addAccessTokenCookie(response, "access");
        service.addRefreshTokenCookie(response, "refresh", Duration.ofDays(7));
        List<String> cookies = headers(response);
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).contains("safeai-access=access", "Path=/", "Max-Age=900", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure", "Domain=");
        assertThat(cookies.get(1)).contains("safeai-refresh=refresh", "Path=/api/auth", "Max-Age=604800", "HttpOnly");
    }

    @Test
    void secureHostAccessAndSecureRefreshAreValid() {
        AuthCookieProperties settings = settings(true, "Lax", null,
                "__Host-safeai-access", "__Secure-safeai-refresh");
        when(settings.accessTokenMaxAge()).thenReturn(Duration.ofMinutes(15));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthCookieService service = new AuthCookieService(settings);
        service.addAccessTokenCookie(response, "a");
        service.addRefreshTokenCookie(response, "r", Duration.ofDays(7));
        assertThat(headers(response)).hasSize(2);
        assertThat(headers(response).get(0)).contains("Secure", "Path=/").doesNotContain("Domain=");
        assertThat(headers(response).get(1)).contains("Secure", "Path=/api/auth").doesNotContain("Domain=");
    }

    @Test
    void invalidHostPrefixedRefreshNeverWritesAHeader() {
        AuthCookieProperties settings = settings(true, "Lax", null,
                "__Host-safeai-access", "__Host-safeai-refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new AuthCookieService(settings)
                .addRefreshTokenCookie(response, "r", Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Path=/");
        assertThat(headers(response)).isEmpty();
    }

    @Test
    void rejectsNoneWithoutSecureForIssuanceAndExpiry() {
        AuthCookieProperties settings = settings(false, "None", null,
                "safeai-access", "safeai-refresh");
        when(settings.accessTokenMaxAge()).thenReturn(Duration.ofMinutes(15));
        AuthCookieService service = new AuthCookieService(settings);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.addAccessTokenCookie(response, "a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure=true");
        assertThatThrownBy(() -> service.clearAuthCookies(response))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure=true");
        assertThat(headers(response)).isEmpty();
    }

    @Test
    void expiresKnownLegacyScopesWithoutDuplicatesOrCsrf() {
        AuthCookieProperties settings = settings(false, "Lax", null,
                "safeai-access", "safeai-refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthCookieService(settings).clearAuthCookies(response);
        List<String> cookies = headers(response);
        assertThat(cookies).hasSize(new HashSet<>(cookies).size());
        assertThat(cookies).allSatisfy(cookie -> assertThat(cookie)
                .contains("Max-Age=0", "HttpOnly")
                .doesNotContain("XSRF-TOKEN"));
        assertExpired(cookies, "access_token", "/");
        assertExpired(cookies, "refresh_token", "/");
        assertExpired(cookies, "refresh_token", "/api/auth");
        assertExpired(cookies, "safeai-refresh", "/api/auth");
        assertExpired(cookies, "__Host-safeai-access", "/");
        assertExpired(cookies, "__Host-safeai-refresh", "/");
        assertThat(cookies).filteredOn(c -> c.startsWith("__Host-") || c.startsWith("__Secure-"))
                .allSatisfy(c -> assertThat(c).contains("Secure"));
    }

    @Test
    void domainMigrationExpiresHostOnlyAndDomainScopedIdentities() {
        AuthCookieProperties settings = settings(true, "None", "example.com",
                "safeai-access", "__Secure-safeai-refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthCookieService(settings).clearAuthCookies(response);
        List<String> cookies = headers(response);
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .startsWith("safeai-access=").contains("Path=/", "Max-Age=0")
                .doesNotContain("Domain="));
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .startsWith("safeai-access=").contains("Domain=example.com", "Max-Age=0"));
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .startsWith("__Secure-safeai-refresh=").contains("Domain=example.com", "Path=/api/auth"));
        assertThat(cookies).filteredOn(c -> c.startsWith("__Host-"))
                .allSatisfy(c -> assertThat(c).doesNotContain("Domain="));
        assertThat(cookies).allSatisfy(c -> assertThat(c).contains("Secure", "SameSite=None"));
    }

    @Test
    void rejectsEmptyAndNonPositiveIssuanceWithoutMutation() {
        AuthCookieProperties settings = settings(false, "Lax", null,
                "safeai-access", "safeai-refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthCookieService service = new AuthCookieService(settings);
        assertThatThrownBy(() -> service.addAccessTokenCookie(response, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addRefreshTokenCookie(response, "token", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(headers(response)).isEmpty();
    }

    @Test
    void extractsOnlyNamedAndNonblankTokens() {
        AuthCookieProperties settings = settings(false, "Lax", null,
                "safeai-access", "safeai-refresh");
        AuthCookieService service = new AuthCookieService(settings);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("safeai-access", "a"), new Cookie("safeai-refresh", "r"));
        assertThat(service.extractAccessToken(request)).isEqualTo("a");
        assertThat(service.extractRefreshToken(request)).isEqualTo("r");
        request.setCookies(new Cookie("safeai-refresh", " "));
        assertThat(service.extractRefreshToken(request)).isNull();
    }

    private static AuthCookieProperties settings(boolean secure, String sameSite,
                                                  String domain, String accessName,
                                                  String refreshName) {
        AuthCookieProperties properties = mock(AuthCookieProperties.class);
        lenient().when(properties.secure()).thenReturn(secure);
        lenient().when(properties.sameSite()).thenReturn(sameSite);
        lenient().when(properties.hasDomain()).thenReturn(domain != null);
        if (domain != null) lenient().when(properties.domain()).thenReturn(domain);
        lenient().when(properties.accessTokenName()).thenReturn(accessName);
        lenient().when(properties.refreshTokenName()).thenReturn(refreshName);
        return properties;
    }

    private static List<String> headers(MockHttpServletResponse response) {
        return List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
    }

    private static void assertExpired(List<String> headers, String name, String path) {
        assertThat(headers).anySatisfy(header -> assertThat(header)
                .startsWith(name + "=")
                .contains("Path=" + path, "Max-Age=0"));
    }
}
