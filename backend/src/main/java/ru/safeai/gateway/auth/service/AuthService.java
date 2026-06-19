package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.dto.AuthUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.auth.mapper.AuthUserMapper;
import ru.safeai.gateway.common.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.Set;
import java.util.stream.Collectors;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuthEventService authEventService;
    private final AuthUserMapper authUserMapper;
    private final UserRepository userRepository;
    private final LoginRateLimitService loginRateLimitService;

    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        Objects.requireNonNull(request, "request не должен быть null");

        String email = Objects.requireNonNull(request.email(), "email не должен быть null")
                .trim()
                .toLowerCase();

        loginRateLimitService.checkAllowed(email, extractClientIp(httpRequest));

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

            String token = jwtService.generateToken(principal);

            authEventService.loginSuccess(principal, httpRequest);

            AuthUserResponse user = authUserMapper.toResponse(principal);

            return new LoginResponse(token, "Bearer", user);

        } catch (AuthenticationException exception) {
            authEventService.loginFailed(email, httpRequest);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(SafeAiUserPrincipal principal) {
        UserEntity user = userRepository.findByIdWithRolesAndOrganization(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + principal.getId()
                ));

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

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");

        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    private String getHeaderOrUnknown(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? "unknown" : value;
    }
}