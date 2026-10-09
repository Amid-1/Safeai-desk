package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.common.exception.AuthServiceUnavailableException;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.ratelimit.RefreshRateLimitService;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.validation.UserEmailNormalizer;

import java.util.Objects;

/**
 * Application-boundary authentication coordinator.
 *
 * <p>Authentication and refresh rotation are committed by dedicated
 * transaction services before HTTP cookies are written.</p>
 */
@Service
public class AuthService {

    private static final String AUTH_UNAVAILABLE_INTERNAL_MESSAGE =
            "Authentication infrastructure is unavailable";

    private final AuthenticationManager authenticationManager;
    private final LoginSessionTransactionService loginSessionTransactionService;
    private final RefreshSessionTransactionService refreshSessionTransactionService;
    private final JwtService jwtService;
    private final AuthEventService authEventService;
    private final UserRepository userRepository;
    private final LoginRateLimitService loginRateLimitService;
    private final RefreshRateLimitService refreshRateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieService authCookieService;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthService(
            AuthenticationManager authenticationManager,
            LoginSessionTransactionService loginSessionTransactionService,
            RefreshSessionTransactionService refreshSessionTransactionService,
            JwtService jwtService,
            AuthEventService authEventService,
            UserRepository userRepository,
            LoginRateLimitService loginRateLimitService,
            RefreshRateLimitService refreshRateLimitService,
            ClientIpResolver clientIpResolver,
            RefreshTokenService refreshTokenService,
            AuthCookieService authCookieService,
            CsrfTokenRepository csrfTokenRepository
    ) {
        this.authenticationManager = Objects.requireNonNull(authenticationManager);
        this.loginSessionTransactionService = Objects.requireNonNull(loginSessionTransactionService);
        this.refreshSessionTransactionService = Objects.requireNonNull(refreshSessionTransactionService);
        this.jwtService = Objects.requireNonNull(jwtService);
        this.authEventService = Objects.requireNonNull(authEventService);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.loginRateLimitService = Objects.requireNonNull(loginRateLimitService);
        this.refreshRateLimitService = Objects.requireNonNull(refreshRateLimitService);
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver);
        this.refreshTokenService = Objects.requireNonNull(refreshTokenService);
        this.authCookieService = Objects.requireNonNull(authCookieService);
        this.csrfTokenRepository = Objects.requireNonNull(csrfTokenRepository);
    }

    /**
     * Reject malformed input before any external side effect.
     * Existing passwords are authenticated as supplied; password creation
     * policy must not retroactively invalidate old credentials.
     */
    public CurrentUserResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(httpRequest, "httpRequest не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        // Important: this is application-boundary validation, not solely
        // controller/Bean Validation. No IP lookup, Redis or auth on failure.
        String email = UserEmailNormalizer.normalizeAndValidate(request.email());
        String password = Objects.requireNonNull(
                request.password(), "password не должен быть null");

        String ipAddress = clientIpResolver.resolve(httpRequest);

        // Fail closed if the limit backend is unavailable. Never produce
        // a duplicate auth failure audit for a limiter rejection.
        loginRateLimitService.checkAllowed(email, ipAddress);

        LoginSessionResult session;
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password));

            if (!(authentication.getPrincipal() instanceof SafeAiUserPrincipal principal)) {
                throw new AuthenticationServiceException(
                        "Некорректный principal после аутентификации");
            }

            // Spring proxy returns only after the session transaction commits.
            session = loginSessionTransactionService.createSession(
                    principal, httpRequest);
        } catch (BadCredentialsException | AccountStatusException exception) {
            authEventService.loginFailed(email, httpRequest);
            throw exception;
        } catch (AuthenticationException | DataAccessException
                 | TransactionException exception) {
            throw authServiceUnavailable(exception);
        }

        // Token generation and response mutation follow successful commit.
        String accessToken = jwtService.generateToken(session.accessTokenSubject());

        authEventService.loginSuccess(session.currentUser(), httpRequest);
        loginRateLimitService.onLoginSuccess(email, ipAddress);
        csrfTokenRepository.saveToken(null, httpRequest, response);
        authCookieService.addAccessTokenCookie(response, accessToken);
        authCookieService.addRefreshTokenCookie(
                response, session.rawRefreshToken(), session.refreshCookieMaxAge());
        return session.currentUser();
    }

    public void refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        // Check anonymous rate limit before touching credentials or DB.
        refreshRateLimitService.checkAllowed(clientIpResolver.resolve(request));

        String rawRefreshToken = authCookieService.extractRefreshToken(request);
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            clearAuthCookies(response);
            throw new InvalidRefreshTokenException("Refresh token не найден");
        }

        try {
            // Rotation plus access-token signing finishes before writing cookies.
            // The session transaction service owns rotation and its audit.
            RefreshSessionResult session =
                    refreshSessionTransactionService.rotateAndSign(rawRefreshToken, request);

            authCookieService.addAccessTokenCookie(response, session.accessToken());
            authCookieService.addRefreshTokenCookie(
                    response, session.rawRefreshToken(), session.refreshCookieMaxAge());
        } catch (RefreshTokenReuseDetectedException exception) {
            // Reuse is audited by the transactional rotation layer; do not
            // duplicate the incident via best-effort auth audit.
            clearAuthCookies(response);
            throw exception;
        } catch (ExpiredRefreshTokenException | InvalidRefreshTokenException exception) {
            clearAuthCookies(response);
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            // Transient infrastructure failures preserve existing cookies for retry.
            throw authServiceUnavailable(exception);
        }
    }

    /**
     * Logout remains idempotent for absent, malformed and revoked tokens.
     * Clear browser state even if revocation cannot be confirmed.
     */
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        try {
            String rawRefreshToken = authCookieService.extractRefreshToken(request);
            if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
                refreshTokenService.revokeFamilyAndReturnSubject(rawRefreshToken)
                        .ifPresent(subject -> authEventService.logout(subject, request));
            }
        } catch (InvalidRefreshTokenException ignored) {
            // Explicit idempotence for malformed/revoked credentials.
        } catch (DataAccessException | TransactionException exception) {
            throw authServiceUnavailable(exception);
        } finally {
            clearAuthCookies(response);
            csrfTokenRepository.saveToken(null, request, response);
        }
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(SafeAiUserPrincipal principal) {
        Objects.requireNonNull(principal, "principal не должен быть null");

        UserEntity user = userRepository
                .findByIdWithRolesAndOrganization(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + principal.getId()));

        return new CurrentUserResponse(
                user.getId(),
                user.getOrganization().getId(),
                UserEmailNormalizer.normalizeStored(user.getEmail()),
                user.getFullName(),
                user.isEnabled() && user.getOrganization().isEnabled(),
                UserRoleMapper.toRoleNames(user)
        );
    }

    private void clearAuthCookies(HttpServletResponse response) {
        authCookieService.clearAuthCookies(response);
    }

    private AuthServiceUnavailableException authServiceUnavailable(RuntimeException cause) {
        return new AuthServiceUnavailableException(
                AUTH_UNAVAILABLE_INTERNAL_MESSAGE, cause);
    }
}
