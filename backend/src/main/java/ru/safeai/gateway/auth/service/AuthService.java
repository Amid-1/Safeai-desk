package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.dto.AuthUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.auth.mapper.AuthUserMapper;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditEventService auditEventService;
    private final AuthUserMapper authUserMapper;

    public LoginResponse login(LoginRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");

        String email = Objects.requireNonNull(request.email(), "email не должен быть null")
                .trim()
                .toLowerCase();

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

            auditEventService.record(
                    principal.getId(),
                    AuditEventType.USER_LOGIN_SUCCESS,
                    Map.of(
                            "email", principal.getEmail(),
                            "organizationId", principal.getOrganizationId().toString()
                    )
            );

            AuthUserResponse user = authUserMapper.toResponse(principal);

            return new LoginResponse(token, "Bearer", user);

        } catch (AuthenticationException exception) {
            auditEventService.record(
                    null,
                    AuditEventType.USER_LOGIN_FAILED,
                    Map.of(
                            "email", email,
                            "reason", "BAD_CREDENTIALS_OR_DISABLED"
                    )
            );

            throw exception;
        }
    }
}