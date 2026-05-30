package ru.safeai.gateway.user.dto;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID organizationId,
        String email,
        String fullName,
        Boolean enabled,
        Set<String> roles,
        LocalDateTime createdAt
) {
}