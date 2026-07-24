package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.transaction.TransactionSystemException;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.common.exception.AuthServiceUnavailableException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USER_ID = UUID.fromString(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    );

    private static final UUID ORGANIZATION_ID = UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    );

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private LoginSessionTransactionService
            loginSessionTransactionService;

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
    private AuthCookieService authCookieService;

    @Mock
    private CsrfTokenRepository csrfTokenRepository;

    @Mock
    private LoginSessionResult loginSessionResult;

    private AuthService authService;

    @BeforeEach
    void setUp() {
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

    @Test
    void validLoginCreatesCommittedSessionThenWritesCookies() {
        LoginRequest request = new LoginRequest(
                " Admin@Test.COM ",
                "legacy-password"
        );

        MockHttpServletRequest httpRequest = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        SafeAiUserPrincipal principal =
                passwordPrincipal();

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        AccessTokenSubject tokenSubject =
                accessTokenSubject();
        CurrentUserResponse currentUser =
                currentUserResponse();
        Duration refreshMaxAge = Duration.ofDays(30);

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any()))
                .thenReturn(authentication);
        when(loginSessionTransactionService.createSession(
                principal,
                httpRequest
        )).thenReturn(loginSessionResult);
        when(loginSessionResult.accessTokenSubject())
                .thenReturn(tokenSubject);
        when(loginSessionResult.currentUser())
                .thenReturn(currentUser);
        when(loginSessionResult.rawRefreshToken())
                .thenReturn("refresh-token");
        when(loginSessionResult.refreshCookieMaxAge())
                .thenReturn(refreshMaxAge);
        when(jwtService.generateToken(tokenSubject))
                .thenReturn("access-token");

        CurrentUserResponse result = authService.login(
                request,
                httpRequest,
                response
        );

        assertThat(result).isSameAs(currentUser);

        verify(loginRateLimitService).checkAllowed(
                "admin@test.com",
                "127.0.0.1"
        );

        InOrder order = inOrder(
                loginSessionTransactionService,
                jwtService,
                csrfTokenRepository,
                authCookieService
        );

        order.verify(loginSessionTransactionService)
                .createSession(principal, httpRequest);
        order.verify(jwtService)
                .generateToken(tokenSubject);
        order.verify(csrfTokenRepository)
                .saveToken(null, httpRequest, response);
        order.verify(authCookieService)
                .addAccessTokenCookie(
                        response,
                        "access-token"
                );
        order.verify(authCookieService)
                .addRefreshTokenCookie(
                        response,
                        "refresh-token",
                        refreshMaxAge
                );

        verify(authEventService).loginSuccess(
                currentUser,
                httpRequest
        );
        verify(loginRateLimitService)
                .resetEmailLimit("admin@test.com");
    }

    @Test
    void badPasswordReturnsAuthenticationFailureWithoutSession() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "wrong-password"
        );

        MockHttpServletRequest httpRequest = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException(
                        "Bad credentials"
                ));

        assertThatThrownBy(() -> authService.login(
                request,
                httpRequest,
                response
        )).isInstanceOf(BadCredentialsException.class);

        verify(authEventService).loginFailed(
                "admin@test.com",
                httpRequest
        );
        verifyNoInteractions(
                loginSessionTransactionService,
                jwtService,
                authCookieService,
                csrfTokenRepository
        );
    }

    @Test
    void disabledAccountUsesSameAuthenticationFailurePath() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "password"
        );

        MockHttpServletRequest httpRequest = request();

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> authService.login(
                request,
                httpRequest,
                new MockHttpServletResponse()
        )).isInstanceOf(DisabledException.class);

        verify(authEventService).loginFailed(
                "admin@test.com",
                httpRequest
        );
    }

    @Test
    void loginAcceptsExistingPasswordEvenIfItDoesNotMatchNewCreationPolicy() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "old"
        );

        MockHttpServletRequest httpRequest = request();
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any()))
                .thenReturn(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal,
                                null,
                                principal.getAuthorities()
                        )
                );
        when(loginSessionTransactionService.createSession(
                principal,
                httpRequest
        )).thenReturn(loginSessionResult);
        when(loginSessionResult.accessTokenSubject())
                .thenReturn(accessTokenSubject());
        when(loginSessionResult.currentUser())
                .thenReturn(currentUserResponse());
        when(loginSessionResult.rawRefreshToken())
                .thenReturn("refresh-token");
        when(loginSessionResult.refreshCookieMaxAge())
                .thenReturn(Duration.ofDays(30));
        when(jwtService.generateToken(any(AccessTokenSubject.class)))
                .thenReturn("access-token");

        authService.login(
                request,
                httpRequest,
                new MockHttpServletResponse()
        );

        verify(authenticationManager).authenticate(
                any(UsernamePasswordAuthenticationToken.class)
        );
    }

    @Test
    void transactionCommitFailureDoesNotWriteAuthCookies() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "password"
        );

        MockHttpServletRequest httpRequest = request();
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any()))
                .thenReturn(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal,
                                null,
                                principal.getAuthorities()
                        )
                );
        when(loginSessionTransactionService.createSession(
                principal,
                httpRequest
        )).thenThrow(new TransactionSystemException(
                "commit failed"
        ));

        assertThatThrownBy(() -> authService.login(
                request,
                httpRequest,
                new MockHttpServletResponse()
        )).isInstanceOf(AuthServiceUnavailableException.class);

        verifyNoInteractions(
                jwtService,
                authCookieService,
                csrfTokenRepository
        );
    }

    @Test
    void rateLimitDbOutageBecomes503() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "password"
        );

        MockHttpServletRequest httpRequest = request();

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        doThrow(new DataAccessResourceFailureException(
                "database unavailable"
        )).when(loginRateLimitService).checkAllowed(
                "admin@test.com",
                "127.0.0.1"
        );

        assertThatThrownBy(() -> authService.login(
                request,
                httpRequest,
                new MockHttpServletResponse()
        )).isInstanceOf(AuthServiceUnavailableException.class);

        verifyNoInteractions(authenticationManager);
    }

    @Test
    void rateLimitExceededIsPreservedAndAudited() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "password"
        );

        MockHttpServletRequest httpRequest = request();
        RateLimitExceededException exception =
                new RateLimitExceededException(
                        "Слишком много попыток",
                        Duration.ofSeconds(60)
                );

        when(clientIpResolver.resolve(httpRequest))
                .thenReturn("127.0.0.1");
        doThrow(exception).when(loginRateLimitService)
                .checkAllowed(
                        "admin@test.com",
                        "127.0.0.1"
                );

        assertThatThrownBy(() -> authService.login(
                request,
                httpRequest,
                new MockHttpServletResponse()
        )).isSameAs(exception);

        verify(authEventService)
                .loginRateLimitExceeded(
                        "admin@test.com",
                        httpRequest,
                        exception
                );
    }

    @Test
    void successfulRefreshRotatesBothCookies() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        AccessTokenSubject subject =
                accessTokenSubject();
        Duration maxAge = Duration.ofDays(30);

        RefreshTokenService.RefreshTokenRotationResult rotation =
                new RefreshTokenService.RefreshTokenRotationResult(
                        subject,
                        "new-refresh-token",
                        maxAge
                );

        when(authCookieService.extractRefreshToken(request))
                .thenReturn("old-refresh-token");
        when(refreshTokenService.rotate(
                "old-refresh-token",
                request
        )).thenReturn(rotation);
        when(jwtService.generateToken(subject))
                .thenReturn("new-access-token");

        authService.refresh(request, response);

        verify(authCookieService).addAccessTokenCookie(
                response,
                "new-access-token"
        );
        verify(authCookieService).addRefreshTokenCookie(
                response,
                "new-refresh-token",
                maxAge
        );
    }

    @Test
    void refreshReuseAuditsAndClearsCookies() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RefreshTokenReuseDetectedException exception =
                new RefreshTokenReuseDetectedException(
                        "reuse",
                        USER_ID,
                        ORGANIZATION_ID,
                        UUID.randomUUID()
                );

        when(authCookieService.extractRefreshToken(request))
                .thenReturn("refresh-token");
        when(refreshTokenService.rotate(
                "refresh-token",
                request
        )).thenThrow(exception);

        assertThatThrownBy(() ->
                authService.refresh(request, response)
        ).isSameAs(exception);

        verify(authEventService)
                .refreshReuseDetected(exception, request);
        verify(authCookieService)
                .clearAuthCookies(response);
    }

    @Test
    void refreshDbOutagePreservesCookiesForRetry() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(authCookieService.extractRefreshToken(request))
                .thenReturn("refresh-token");
        when(refreshTokenService.rotate(
                "refresh-token",
                request
        )).thenThrow(new DataAccessResourceFailureException(
                "database unavailable"
        ));

        assertThatThrownBy(() ->
                authService.refresh(request, response)
        ).isInstanceOf(AuthServiceUnavailableException.class);

        verify(authCookieService, never())
                .clearAuthCookies(response);
    }

    @Test
    void logoutRevokesFamilyAuditsAndAlwaysClearsCookies() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        LogoutAuditSubject subject = new LogoutAuditSubject(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com"
        );

        when(authCookieService.extractRefreshToken(request))
                .thenReturn("refresh-token");
        when(refreshTokenService.revokeFamilyAndReturnSubject(
                "refresh-token"
        )).thenReturn(Optional.of(subject));

        authService.logout(request, response);

        verify(authEventService).logout(subject, request);
        verify(authCookieService).clearAuthCookies(response);
        verify(csrfTokenRepository).saveToken(
                null,
                request,
                response
        );
    }

    @Test
    void malformedLogoutTokenRemainsIdempotent() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        when(authCookieService.extractRefreshToken(request))
                .thenReturn("malformed");
        when(refreshTokenService.revokeFamilyAndReturnSubject(
                "malformed"
        )).thenThrow(new InvalidRefreshTokenException(
                "invalid"
        ));

        authService.logout(request, response);

        verify(authEventService, never()).logout(
                any(LogoutAuditSubject.class),
                any()
        );
        verify(authCookieService).clearAuthCookies(response);
        verify(csrfTokenRepository).saveToken(
                null,
                request,
                response
        );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private SafeAiUserPrincipal passwordPrincipal() {
        return SafeAiUserPrincipal.passwordPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "encoded-password",
                true,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }

    private AccessTokenSubject accessTokenSubject() {
        return new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                0L,
                Set.of("ADMIN")
        );
    }

    private CurrentUserResponse currentUserResponse() {
        return new CurrentUserResponse(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "Demo Admin",
                true,
                Set.of("ADMIN")
        );
    }
}
