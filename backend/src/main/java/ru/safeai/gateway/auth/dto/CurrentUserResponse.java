package ru.safeai.gateway.auth.dto;

import java.util.Set;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        UUID organizationId,
        String email,
        String fullName,
        boolean enabled,
        Set<String> roles
) {
    public CurrentUserResponse {
        roles = Set.copyOf(roles);
    }
}
