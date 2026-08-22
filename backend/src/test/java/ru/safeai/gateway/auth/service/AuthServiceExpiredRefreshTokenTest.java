package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceExpiredRefreshTokenTest {

    private static final String ACCESS_COOKIE =
            "safeai-access";

    private static final String REFRESH_COOKIE =
            "safeai-refresh";

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private LoginSessionTransactionService loginSessionTransactionService;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthEventService authEventService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LoginRateLimitService loginRateLimitService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private CsrfTokenRepository csrfTokenRepository;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthCookieService authCookieService = getAuthCookieService();

        authService = new AuthService(
                authenticationManager,
                loginSessionTransactionService,
                jwtService,
                authEventService,
                userRepository,
                loginRateLimitService,
                clientIpResolver,
                refreshTokenService,
                authCookieService,
                csrfTokenRepository
        );
    }

    private static @NonNull AuthCookieService getAuthCookieService() {
        AuthCookieProperties cookieProperties =
                new AuthCookieProperties(
                        false,
                        "Lax",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofDays(90),
                        Duration.ofDays(7),
                        null,
                        ACCESS_COOKIE,
                        REFRESH_COOKIE
                );

        return new AuthCookieService(
                cookieProperties
        );
    }

    @Test
    void expiredRefreshTokenClearsAccessAndRefreshCookiesAndPreservesException() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setCookies(
                new Cookie(
                        REFRESH_COOKIE,
                        "expired-refresh-token"
                )
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        ExpiredRefreshTokenException exception =
                new ExpiredRefreshTokenException(
                        "Refresh token expired"
                );

        assertThat(exception.getStatus().value())
                .isEqualTo(
                        HttpStatus.UNAUTHORIZED.value()
                );

        assertThat(exception.getErrorCode())
                .isEqualTo(
                        ApiErrorCode.EXPIRED_REFRESH_TOKEN
                );

        when(refreshTokenService.rotate(
                "expired-refresh-token",
                request
        )).thenThrow(exception);

        assertThatThrownBy(() ->
                authService.refresh(
                        request,
                        response
                )
        ).isSameAs(exception);

        List<String> setCookieHeaders =
                response.getHeaders(
                        HttpHeaders.SET_COOKIE
                );

        assertThat(setCookieHeaders)
                .anySatisfy(header -> assertThat(header)
                        .startsWith(
                                ACCESS_COOKIE + "="
                        )
                        .contains("Max-Age=0")
                        .contains("Path=/"));

        assertThat(setCookieHeaders)
                .anySatisfy(header -> assertThat(header)
                        .startsWith(
                                REFRESH_COOKIE + "="
                        )
                        .contains("Max-Age=0")
                        .contains("Path=/api/auth"));
    }
}
