package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.ratelimit.RefreshRateLimitService;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceRefreshReuseAuditRegressionTest {

    @Test
    void refreshReuseDoesNotEmitSecondBestEffortAudit() {
        AuthenticationManager authenticationManager =
                mock(
                        AuthenticationManager.class
                );

        LoginSessionTransactionService loginSessionTransactionService =
                mock(
                        LoginSessionTransactionService.class
                );

        RefreshSessionTransactionService refreshSessionTransactionService =
                mock(
                        RefreshSessionTransactionService.class
                );

        JwtService jwtService =
                mock(
                        JwtService.class
                );

        AuthEventService authEventService =
                mock(
                        AuthEventService.class
                );

        UserRepository userRepository =
                mock(
                        UserRepository.class
                );

        LoginRateLimitService loginRateLimitService =
                mock(
                        LoginRateLimitService.class
                );

        RefreshRateLimitService refreshRateLimitService =
                mock(
                        RefreshRateLimitService.class
                );

        ClientIpResolver clientIpResolver =
                mock(
                        ClientIpResolver.class
                );

        RefreshTokenService refreshTokenService =
                mock(
                        RefreshTokenService.class
                );

        AuthCookieService authCookieService =
                mock(
                        AuthCookieService.class
                );

        CsrfTokenRepository csrfTokenRepository =
                mock(
                        CsrfTokenRepository.class
                );

        AuthService service =
                new AuthService(
                        authenticationManager,
                        loginSessionTransactionService,
                        refreshSessionTransactionService,
                        jwtService,
                        authEventService,
                        userRepository,
                        loginRateLimitService,
                        refreshRateLimitService,
                        clientIpResolver,
                        refreshTokenService,
                        authCookieService,
                        csrfTokenRepository
                );

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(
                clientIpResolver.resolve(
                        request
                )
        ).thenReturn(
                "127.0.0.1"
        );

        when(
                authCookieService.extractRefreshToken(
                        request
                )
        ).thenReturn(
                "refresh-token"
        );

        RefreshTokenReuseDetectedException exception =
                new RefreshTokenReuseDetectedException(
                        "reuse",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()
                );

        when(
                refreshSessionTransactionService
                        .rotateAndSign(
                                "refresh-token",
                                request
                        )
        ).thenThrow(
                exception
        );

        assertThatThrownBy(() ->
                service.refresh(
                        request,
                        response
                )
        ).isSameAs(
                exception
        );

        verify(
                authCookieService
        ).clearAuthCookies(
                response
        );

        /*
         * Reuse evidence is committed atomically inside refresh rotation.
         * AuthService must not emit a second best-effort auth event.
         */
        verifyNoInteractions(
                authEventService
        );

        verifyNoInteractions(
                refreshTokenService,
                jwtService
        );

        verify(
                authCookieService,
                never()
        ).addAccessTokenCookie(
                same(
                        response
                ),
                anyString()
        );
    }
}
