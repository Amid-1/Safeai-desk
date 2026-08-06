package ru.safeai.gateway.organization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        boolean enabled,
        OrganizationType type,
        @JsonProperty("protected") boolean protectedOrganization,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public OrganizationResponse(
            UUID id,
            String name,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                name,
                enabled,
                OrganizationType.TENANT,
                false,
                0L,
                createdAt,
                updatedAt
        );
    }
}
