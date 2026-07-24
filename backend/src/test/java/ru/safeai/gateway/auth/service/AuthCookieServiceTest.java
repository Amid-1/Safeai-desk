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

    private static final String ACCESS_NAME =
            "safeai_access";

    private static final String REFRESH_NAME =
            "safeai_refresh";

    @Mock
    private AuthCookieProperties properties;

    private AuthCookieService service;

    @BeforeEach
    void setUp() {
        service = new AuthCookieService(properties);
    }

    @Test
    void addAccessTokenCookieUsesProductionAttributes() {
        stubAccessTokenCookieProperties();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        service.addAccessTokenCookie(
                response,
                "access-token"
        );

        String cookie = onlySetCookie(response);

        assertThat(cookie)
                .contains(ACCESS_NAME + "=access-token")
                .contains("Path=/")
                .contains("Max-Age=900")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure")
                .doesNotContain("Domain=");
    }

    @Test
    void addRefreshTokenCookieUsesRestrictedPathAndProvidedMaxAge() {
        stubRefreshTokenCookieProperties();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        service.addRefreshTokenCookie(
                response,
                "refresh-token",
                Duration.ofDays(7)
        );

        String cookie = onlySetCookie(response);

        assertThat(cookie)
                .contains(REFRESH_NAME + "=refresh-token")
                .contains("Path=/api/auth")
                .contains("Max-Age=604800")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    void clearAuthCookiesClearsCurrentAndLegacyCookiesButNotCsrf() {
        stubAuthCookieNames();
        stubDefaultCookieAttributes();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        service.clearAuthCookies(response);

        List<String> cookies = response.getHeaders(
                HttpHeaders.SET_COOKIE
        );

        assertThat(cookies).hasSize(5);
        assertThat(cookies).allSatisfy(cookie ->
                assertThat(cookie)
                        .contains("Max-Age=0")
                        .contains("HttpOnly")
                        .doesNotContain("XSRF-TOKEN")
        );

        assertThat(cookies).anySatisfy(cookie ->
                assertThat(cookie)
                        .contains(ACCESS_NAME + "=")
                        .contains("Path=/")
        );

        assertThat(cookies).anySatisfy(cookie ->
                assertThat(cookie)
                        .contains(REFRESH_NAME + "=")
                        .contains("Path=/api/auth")
        );

        assertThat(cookies).anySatisfy(cookie ->
                assertThat(cookie)
                        .contains("access_token=")
                        .contains("Path=/")
        );

        assertThat(cookies).anySatisfy(cookie ->
                assertThat(cookie)
                        .contains("refresh_token=")
                        .contains("Path=/")
        );

        assertThat(cookies).anySatisfy(cookie ->
                assertThat(cookie)
                        .contains("refresh_token=")
                        .contains("Path=/api/auth")
        );
    }

    @Test
    void extractRefreshTokenReadsConfiguredCookieName() {
        when(properties.refreshTokenName())
                .thenReturn(REFRESH_NAME);

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setCookies(
                new Cookie(
                        ACCESS_NAME,
                        "access-token"
                ),
                new Cookie(
                        REFRESH_NAME,
                        "refresh-token"
                )
        );

        assertThat(service.extractRefreshToken(request))
                .isEqualTo("refresh-token");
    }

    @Test
    void extractRefreshTokenReturnsNullForMissingOrBlankCookie() {
        when(properties.refreshTokenName())
                .thenReturn(REFRESH_NAME);

        MockHttpServletRequest missing =
                new MockHttpServletRequest();

        missing.setCookies(
                new Cookie(
                        ACCESS_NAME,
                        "access-token"
                )
        );

        MockHttpServletRequest blank =
                new MockHttpServletRequest();

        blank.setCookies(
                new Cookie(
                        REFRESH_NAME,
                        " "
                )
        );

        assertThat(service.extractRefreshToken(missing))
                .isNull();

        assertThat(service.extractRefreshToken(blank))
                .isNull();
    }

    @Test
    void cookieCreationRejectsBlankTokenAndNonPositiveMaxAge() {
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertThatThrownBy(() ->
                service.addAccessTokenCookie(
                        response,
                        " "
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );

        assertThatThrownBy(() ->
                service.addRefreshTokenCookie(
                        response,
                        "refresh-token",
                        Duration.ZERO
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }

    @Test
    void cookiesUseSecureAndDomainWhenConfigured() {
        when(properties.accessTokenName())
                .thenReturn(ACCESS_NAME);

        when(properties.accessTokenMaxAge())
                .thenReturn(Duration.ofMinutes(15));

        when(properties.secure())
                .thenReturn(true);

        when(properties.sameSite())
                .thenReturn("Lax");

        when(properties.hasDomain())
                .thenReturn(true);

        when(properties.domain())
                .thenReturn("example.com");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        service.addAccessTokenCookie(
                response,
                "access-token"
        );

        assertThat(onlySetCookie(response))
                .contains("Secure")
                .contains("Domain=example.com");
    }

    private void stubAccessTokenCookieProperties() {
        when(properties.accessTokenName())
                .thenReturn(ACCESS_NAME);

        when(properties.accessTokenMaxAge())
                .thenReturn(Duration.ofMinutes(15));

        stubDefaultCookieAttributes();
    }

    private void stubRefreshTokenCookieProperties() {
        when(properties.refreshTokenName())
                .thenReturn(REFRESH_NAME);

        stubDefaultCookieAttributes();
    }

    private void stubAuthCookieNames() {
        when(properties.accessTokenName())
                .thenReturn(ACCESS_NAME);

        when(properties.refreshTokenName())
                .thenReturn(REFRESH_NAME);
    }

    private void stubDefaultCookieAttributes() {
        when(properties.secure())
                .thenReturn(false);

        when(properties.sameSite())
                .thenReturn("Lax");

        when(properties.hasDomain())
                .thenReturn(false);
    }

    private String onlySetCookie(
            MockHttpServletResponse response
    ) {
        List<String> cookies = response.getHeaders(
                HttpHeaders.SET_COOKIE
        );

        assertThat(cookies).hasSize(1);

        return cookies.getFirst();
    }
}