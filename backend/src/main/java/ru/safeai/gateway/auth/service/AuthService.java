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
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

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

        String email = Objects.requireNonNull(request.email(), "email не должен быть null")
                .trim()
                .toLowerCase();

        loginRateLimitService.checkAllowed(email, clientIpResolver.resolve(httpRequest));

        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            email,
                            request.password()
                    )
            );

            if (!(authentication.getPrincipal() instanceof SafeAiUserPrincipal principal)) {
                throw new IllegalStateException("Некорректный тип principal после авторизации");
            }

            UserEntity user = userRepository.findByIdWithRolesAndOrganization(principal.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Пользователь не найден: " + principal.getId()
                    ));

            String accessToken = jwtService.generateAccessToken(user);
            String refreshToken = refreshTokenService.create(user, httpRequest);

            authCookieService.addAccessTokenCookie(response, accessToken);
            authCookieService.addRefreshTokenCookie(response, refreshToken);

            authEventService.loginSuccess(principal, httpRequest);
            loginRateLimitService.resetEmailLimit(email);

            return toCurrentUserResponse(user);

        } catch (AuthenticationException exception) {
            authEventService.loginFailed(email, httpRequest);
            throw exception;
        }
    }

    @Transactional
    public void refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        String rawRefreshToken = authCookieService.extractRefreshToken(request);

        if (rawRefreshToken == null) {
            throw new ResourceNotFoundException("Refresh token не найден");
        }

        RefreshTokenEntity refreshToken = refreshTokenService.validate(rawRefreshToken);

        UserEntity user = userRepository.findByIdWithRolesAndOrganization(
                        refreshToken.getUser().getId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + refreshToken.getUser().getId()
                ));

        String newAccessToken = jwtService.generateAccessToken(user);

        authCookieService.addAccessTokenCookie(response, newAccessToken);
    }

    @Transactional
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");

        String rawRefreshToken = authCookieService.extractRefreshToken(request);

        if (rawRefreshToken != null) {
            refreshTokenService.revoke(rawRefreshToken);
        }

        authCookieService.clearAuthCookies(response);
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(SafeAiUserPrincipal principal) {
        Objects.requireNonNull(principal, "principal не должен быть null");

        UserEntity user = userRepository.findByIdWithRolesAndOrganization(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + principal.getId()
                ));

        return toCurrentUserResponse(user);
    }

    private CurrentUserResponse toCurrentUserResponse(UserEntity user) {
        Set<String> roles = user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toSet());

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