package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuthEventService authEventService;
    private final UserRepository userRepository;
    private final LoginRateLimitService loginRateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieService authCookieService;

    @Transactional
    public CurrentUserResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(httpRequest, "httpRequest не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        String email = Objects.requireNonNull(
                        request.email(),
                        "email не должен быть null"
                )
                .trim()
                .toLowerCase(Locale.ROOT);

        String password = Objects.requireNonNull(
                request.password(),
                "password не должен быть null"
        );

        try {
            loginRateLimitService.checkAllowed(
                    email,
                    clientIpResolver.resolve(httpRequest)
            );
        } catch (RateLimitExceededException exception) {
            authEventService.loginRateLimitExceeded(
                    email,
                    httpRequest,
                    exception
            );
            throw exception;
        }

        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            email,
                            password
                    )
            );

            if (!(authentication.getPrincipal()
                    instanceof SafeAiUserPrincipal principal)) {
                throw new IllegalStateException(
                        "Некорректный тип principal после авторизации"
                );
            }

            UserEntity user = userRepository
                    .findByIdWithRolesAndOrganization(principal.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Пользователь не найден: " + principal.getId()
                    ));

            Instant lastLoginAt = Instant.now();

            int updatedRows = userRepository.updateLastLoginAt(
                    user.getId(),
                    lastLoginAt
            );

            if (updatedRows != 1) {
                throw new IllegalStateException(
                        "Не удалось обновить время последнего входа пользователя: "
                                + user.getId()
                );
            }

            user.setLastLoginAt(lastLoginAt);

            AccessTokenSubject accessTokenSubject =
                    toAccessTokenSubject(user);

            String accessToken =
                    jwtService.generateToken(accessTokenSubject);

            String refreshToken =
                    refreshTokenService.create(user, httpRequest);

            authEventService.loginSuccess(principal, httpRequest);
            loginRateLimitService.resetEmailLimit(email);

            authCookieService.addAccessTokenCookie(
                    response,
                    accessToken
            );

            authCookieService.addRefreshTokenCookie(
                    response,
                    refreshToken
            );

            return toCurrentUserResponse(user);

        } catch (AuthenticationException exception) {
            authEventService.loginFailed(email, httpRequest);
            throw exception;
        }
    }

    public void refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        String rawRefreshToken =
                authCookieService.extractRefreshToken(request);

        if (rawRefreshToken == null) {
            authCookieService.clearAuthCookies(response);
            throw new InvalidRefreshTokenException(
                    "Refresh token не найден"
            );
        }

        try {
            RefreshTokenService.RefreshTokenRotationResult rotationResult =
                    refreshTokenService.rotate(rawRefreshToken, request);

            String newAccessToken = jwtService.generateToken(
                    rotationResult.accessTokenSubject()
            );

            authCookieService.addAccessTokenCookie(
                    response,
                    newAccessToken
            );
            authCookieService.addRefreshTokenCookie(
                    response,
                    rotationResult.rawRefreshToken()
            );
        } catch (RefreshTokenReuseDetectedException exception) {
            authEventService.refreshReuseDetected(exception, request);
            authCookieService.clearAuthCookies(response);
            throw exception;
        } catch (InvalidRefreshTokenException exception) {
            authCookieService.clearAuthCookies(response);
            throw exception;
        }
    }

    public void logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        String rawRefreshToken =
                authCookieService.extractRefreshToken(request);

        if (rawRefreshToken != null) {
            refreshTokenService.revokeAndReturnUser(rawRefreshToken)
                    .ifPresent(user ->
                            authEventService.logout(user, request)
                    );
        }

        authCookieService.clearAuthCookies(response);
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
                .findByIdWithRolesAndOrganization(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + principal.getId()
                ));

        return toCurrentUserResponse(user);
    }

    private AccessTokenSubject toAccessTokenSubject(UserEntity user) {
        Set<String> roles = user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toUnmodifiableSet());

        return new AccessTokenSubject(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getTokenVersion(),
                roles
        );
    }

    private CurrentUserResponse toCurrentUserResponse(UserEntity user) {
        Set<String> roles = user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toUnmodifiableSet());

        return new CurrentUserResponse(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getFullName(),
                user.isEnabled(),
                roles
        );
    }
}
