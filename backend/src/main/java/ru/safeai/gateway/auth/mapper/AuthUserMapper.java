package ru.safeai.gateway.auth.mapper;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.auth.dto.AuthUserResponse;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuthUserMapper {

    public AuthUserResponse toResponse(SafeAiUserPrincipal principal) {
        return new AuthUserResponse(
                principal.getId(),
                principal.getOrganizationId(),
                principal.getEmail(),
                principal.isEnabled(),
                extractRoles(principal)
        );
    }

    private Set<String> extractRoles(SafeAiUserPrincipal principal) {
        return principal.getAuthorities()
                .stream()
                .filter(Objects::nonNull)
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(authority -> authority.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toSet());
    }
}