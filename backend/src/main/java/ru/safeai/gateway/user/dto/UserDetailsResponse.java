package ru.safeai.gateway.user.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserDetailsResponse(
        UUID id,
        UUID organizationId,
        String email,
        String fullName,
        boolean enabled,
        Set<String> roles,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt
) {
}
