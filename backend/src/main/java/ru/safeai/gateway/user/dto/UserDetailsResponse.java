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
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt
) {
    public UserDetailsResponse(
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
        this(
                id,
                organizationId,
                email,
                fullName,
                enabled,
                roles,
                0L,
                createdAt,
                updatedAt,
                lastLoginAt
        );
    }
}
