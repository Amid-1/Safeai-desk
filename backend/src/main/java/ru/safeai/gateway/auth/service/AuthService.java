package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.dto.AuthUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditEventService auditEventService;

    public LoginResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.email(),
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

            Set<String> roles = principal.getAuthorities()
                    .stream()
                    .filter(Objects::nonNull)
                    .map(GrantedAuthority::getAuthority)
                    .filter(Objects::nonNull)
                    .map(authority -> authority.replaceFirst("^ROLE_", ""))
                    .collect(Collectors.toSet());

            AuthUserResponse user = new AuthUserResponse(
                    principal.getId(),
                    principal.getOrganizationId(),
                    principal.getEmail(),
                    principal.isEnabled(),
                    roles
            );

            return new LoginResponse(token, "Bearer", user);

        } catch (AuthenticationException exception) {
            auditEventService.record(
                    null,
                    AuditEventType.USER_LOGIN_FAILED,
                    Map.of(
                            "email", request.email()
                    )
            );

            throw exception;
        }
    }
}