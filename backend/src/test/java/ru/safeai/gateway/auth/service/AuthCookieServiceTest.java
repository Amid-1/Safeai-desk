package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookieServiceTest {

    private AuthCookieService service;

    @BeforeEach
    void setUp() {
        AuthCookieProperties properties = new AuthCookieProperties(
                false,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30)
        );

        service = new AuthCookieService(properties);
    }

    @Test
    void addAccessTokenCookie_shouldAddHttpOnlyAccessCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.addAccessTokenCookie(response, "access-token");

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(cookies).hasSize(1);

        String cookie = cookies.getFirst();

        assertThat(cookie).contains("access_token=access-token");
        assertThat(cookie).contains("Path=/");
        assertThat(cookie).contains("Max-Age=900");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
        assertThat(cookie).doesNotContain("Secure");
    }

    @Test
    void addRefreshTokenCookie_shouldAddHttpOnlyRefreshCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.addRefreshTokenCookie(response, "refresh-token");

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(cookies).hasSize(1);

        String cookie = cookies.getFirst();

        assertThat(cookie).contains("refresh_token=refresh-token");
        assertThat(cookie).contains("Path=/");
        assertThat(cookie).contains("Max-Age=2592000");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
    }

    @Test
    void clearAuthCookies_shouldClearAccessRefreshAndCsrfCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clearAuthCookies(response);

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(cookies).hasSize(3);

        assertThat(cookies)
                .anySatisfy(cookie -> {
                    assertThat(cookie).contains("access_token=");
                    assertThat(cookie).contains("Max-Age=0");
                    assertThat(cookie).contains("HttpOnly");
                });

        assertThat(cookies)
                .anySatisfy(cookie -> {
                    assertThat(cookie).contains("refresh_token=");
                    assertThat(cookie).contains("Max-Age=0");
                    assertThat(cookie).contains("HttpOnly");
                });

        assertThat(cookies)
                .anySatisfy(cookie -> {
                    assertThat(cookie).contains("XSRF-TOKEN=");
                    assertThat(cookie).contains("Max-Age=0");
                    assertThat(cookie).doesNotContain("HttpOnly");
                });
    }

    @Test
    void extractRefreshToken_shouldReturnRefreshTokenFromCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setCookies(
                new Cookie("access_token", "access-token"),
                new Cookie("refresh_token", "refresh-token")
        );

        assertThat(service.extractRefreshToken(request)).isEqualTo("refresh-token");
    }

    @Test
    void extractRefreshToken_shouldReturnNullWhenCookieIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setCookies(new Cookie("access_token", "access-token"));

        assertThat(service.extractRefreshToken(request)).isNull();
    }

    @Test
    void extractRefreshToken_shouldReturnNullWhenCookieValueIsBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setCookies(new Cookie("refresh_token", " "));

        assertThat(service.extractRefreshToken(request)).isNull();
    }

    @Test
    void addAccessTokenCookie_shouldUseSecureFlagWhenEnabled() {
        AuthCookieService secureService = new AuthCookieService(
                new AuthCookieProperties(
                        true,
                        "None",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30)
                )
        );

        MockHttpServletResponse response = new MockHttpServletResponse();

        secureService.addAccessTokenCookie(response, "access-token");

        String cookie = response.getHeaders(HttpHeaders.SET_COOKIE).getFirst();

        assertThat(cookie).contains("Secure");
        assertThat(cookie).contains("SameSite=None");
    }
}