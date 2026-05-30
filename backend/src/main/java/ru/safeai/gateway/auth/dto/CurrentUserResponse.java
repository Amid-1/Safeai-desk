package ru.safeai.gateway.auth.dto;

import java.util.Set;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        UUID organizationId,
        String email,
        String fullName,
        Boolean enabled,
        Set<String> roles
) {
}