package ru.safeai.gateway.auth.dto;

import java.util.Set;
import java.util.UUID;

public record AuthUserResponse(
        UUID id,
        UUID organizationId,
        String email,
        Boolean enabled,
        Set<String> roles
) {
}