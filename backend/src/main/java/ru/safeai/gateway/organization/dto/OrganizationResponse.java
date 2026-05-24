package ru.safeai.gateway.organization.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        LocalDateTime createdAt
) {
}