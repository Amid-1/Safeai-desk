package ru.safeai.gateway.user.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
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
    public UserResponse {
        roles = Set.copyOf(
                Objects.requireNonNull(
                        roles,
                        "roles не должен быть null"
                )
        );
    }
}
