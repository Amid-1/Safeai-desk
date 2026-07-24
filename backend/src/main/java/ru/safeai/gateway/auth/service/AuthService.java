package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_EMAIL_LENGTH = 255;

    private static final String AUTH_UNAVAILABLE_INTERNAL_MESSAGE =
            "Authentication infrastructure is unavailable";

    private final AuthenticationManager authenticationManager;

    private final LoginSessionTransactionService
            loginSessionTransactionService;

    private final JwtService jwtService;
    private final AuthEventService authEventService;
    private final UserRepository userRepository;
    private final LoginRateLimitService loginRateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieService authCookieService;
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * Метод намеренно не является транзакционным.
     *
     * <p>Проверка password hash выполняется до начала короткой
     * транзакции создания login session.</p>
     */
    public CurrentUserResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                httpRequest,
                "httpRequest не должен быть null"
        );

        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        String email = canonicalEmail(
                Objects.requireNonNull(
                        request.email(),
                        "email не должен быть null"
                )
        );

        String password = Objects.requireNonNull(
                request.password(),
                "password не должен быть null"
        );

        checkLoginRateLimit(
                email,
                httpRequest
        );

        LoginSessionResult session;

        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken
                                    .unauthenticated(
                                            email,
                                            password
                                    )
                    );

            if (!(authentication.getPrincipal()
                    instanceof SafeAiUserPrincipal principal)) {
                throw new AuthenticationServiceException(
                        "Некорректный тип principal после аутентификации"
                );
            }

            /*
             * Вызов выполняется через внешний Spring proxy.
             * К моменту возврата транзакция создания сессии уже committed.
             */
            session = loginSessionTransactionService.createSession(
                    principal,
                    httpRequest
            );
        } catch (BadCredentialsException
                 | AccountStatusException exception) {
            authEventService.loginFailed(
                    email,
                    httpRequest
            );

            throw exception;
        } catch (AuthenticationException
                 | DataAccessException
                 | TransactionException exception) {
            throw authServiceUnavailable(exception);
        }

        /*
         * Генерация JWT выполняется после commit login transaction,
         * но до изменения HTTP response.
         */
        String accessToken = jwtService.generateToken(
                session.accessTokenSubject()
        );

        /*
         * Старый anonymous CSRF token удаляется после успешного login.
         * Frontend после login должен снова вызвать GET /api/auth/csrf.
         */
        csrfTokenRepository.saveToken(
                null,
                httpRequest,
                response
        );

        authCookieService.addAccessTokenCookie(
                response,
                accessToken
        );

        authCookieService.addRefreshTokenCookie(
                response,
                session.rawRefreshToken(),
                session.refreshCookieMaxAge()
        );

        authEventService.loginSuccess(
                session.currentUser(),
                httpRequest
        );

        resetEmailRateLimitBestEffort(email);

        return session.currentUser();
    }

    public void refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        String rawRefreshToken =
                authCookieService.extractRefreshToken(request);

        if (rawRefreshToken == null) {
            authCookieService.clearAuthCookies(response);

            throw new InvalidRefreshTokenException(
                    "Refresh token не найден"
            );
        }

        try {
            RefreshTokenService.RefreshTokenRotationResult rotation =
                    refreshTokenService.rotate(
                            rawRefreshToken,
                            request
                    );

            /*
             * Rotation transaction уже committed.
             * Только после этого формируются новые cookies.
             */
            String newAccessToken = jwtService.generateToken(
                    rotation.accessTokenSubject()
            );

            authCookieService.addAccessTokenCookie(
                    response,
                    newAccessToken
            );

            authCookieService.addRefreshTokenCookie(
                    response,
                    rotation.rawRefreshToken(),
                    rotation.refreshCookieMaxAge()
            );
        } catch (RefreshTokenReuseDetectedException exception) {
            authEventService.refreshReuseDetected(
                    exception,
                    request
            );

            clearAuthCookies(response);
            throw exception;
        } catch (InvalidRefreshTokenException exception) {
            /*
             * Сюда также попадает ExpiredRefreshTokenException,
             * если он наследуется от InvalidRefreshTokenException.
             */
            clearAuthCookies(response);
            throw exception;
        } catch (DataAccessException
                 | TransactionException exception) {
            /*
             * При временной недоступности БД cookies сохраняются,
             * чтобы клиент мог повторить refresh.
             */
            throw authServiceUnavailable(exception);
        }
    }

    /**
     * Идемпотентен для отсутствующего и повреждённого refresh token.
     *
     * <p>При DB outage локальные cookies всё равно очищаются,
     * но клиент получает 503, поскольку server-side revocation
     * не была подтверждена.</p>
     */
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        try {
            String rawRefreshToken =
                    authCookieService.extractRefreshToken(request);

            if (rawRefreshToken != null) {
                refreshTokenService
                        .revokeFamilyAndReturnSubject(
                                rawRefreshToken
                        )
                        .ifPresent(subject ->
                                authEventService.logout(
                                        subject,
                                        request
                                )
                        );
            }
        } catch (InvalidRefreshTokenException ignored) {
            /*
             * Logout остаётся идемпотентным для отсутствующего,
             * повреждённого или уже недействительного token.
             */
        } catch (DataAccessException
                 | TransactionException exception) {
            throw authServiceUnavailable(exception);
        } finally {
            clearAuthCookies(response);

            csrfTokenRepository.saveToken(
                    null,
                    request,
                    response
            );
        }
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(
            SafeAiUserPrincipal principal
    ) {
        Objects.requireNonNull(
                principal,
                "principal не должен быть null"
        );

        UserEntity user = userRepository
                .findByIdWithRolesAndOrganization(
                        principal.getId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: "
                                + principal.getId()
                ));

        return new CurrentUserResponse(
                user.getId(),
                user.getOrganization().getId(),
                canonicalEmail(user.getEmail()),
                user.getFullName(),
                user.isEnabled()
                        && user.getOrganization().isEnabled(),
                UserRoleMapper.toRoleNames(user)
        );
    }

    private void checkLoginRateLimit(
            String email,
            HttpServletRequest request
    ) {
        try {
            loginRateLimitService.checkAllowed(
                    email,
                    clientIpResolver.resolve(request)
            );
        } catch (RateLimitExceededException exception) {
            authEventService.loginRateLimitExceeded(
                    email,
                    request,
                    exception
            );

            throw exception;
        } catch (DataAccessException exception) {
            throw authServiceUnavailable(exception);
        }
    }

    private void resetEmailRateLimitBestEffort(
            String email
    ) {
        try {
            loginRateLimitService.resetEmailLimit(email);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to reset login email rate limit "
                            + "after successful login: email={}",
                    email,
                    exception
            );
        }
    }

    private void clearAuthCookies(
            HttpServletResponse response
    ) {
        authCookieService.clearAuthCookies(response);
    }

    private AuthServiceUnavailableException authServiceUnavailable(
            RuntimeException cause
    ) {
        return new AuthServiceUnavailableException(
                AUTH_UNAVAILABLE_INTERNAL_MESSAGE,
                cause
        );
    }

    private String canonicalEmail(
            String email
    ) {
        Objects.requireNonNull(
                email,
                "email не должен быть null"
        );

        String canonical = email
                .trim()
                .toLowerCase(Locale.ROOT);

        if (canonical.isBlank()
                || canonical.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "Некорректный email"
            );
        }

        return canonical;
    }
}