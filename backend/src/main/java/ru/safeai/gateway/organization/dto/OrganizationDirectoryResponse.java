package ru.safeai.gateway.organization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record OrganizationDirectoryResponse(
        UUID id,
        String name,
        boolean enabled,
        OrganizationType type,
        @JsonProperty("protected") boolean protectedOrganization,
        long version
) {
}
