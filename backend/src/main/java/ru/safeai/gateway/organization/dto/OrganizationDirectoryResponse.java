package ru.safeai.gateway.organization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

public record OrganizationDirectoryResponse(
        UUID id,
        String name,
        boolean enabled,
        OrganizationType type,
        @JsonProperty("protected")
        boolean protectedOrganization,
        long version
) {
    public OrganizationDirectoryResponse {
        Objects.requireNonNull(
                id,
                "id не должен быть null"
        );
        Objects.requireNonNull(
                type,
                "type не должен быть null"
        );

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "name не должен быть пустым"
            );
        }

        if (version < 0L) {
            throw new IllegalArgumentException(
                    "version не может быть отрицательной"
            );
        }

        if (
                type == OrganizationType.PLATFORM
                && !protectedOrganization
        ) {
            throw new IllegalArgumentException(
                    "PLATFORM organization должна быть protected"
            );
        }

        if (
                type == OrganizationType.TENANT
                && protectedOrganization
        ) {
            throw new IllegalArgumentException(
                    "TENANT organization не может быть protected"
            );
        }
    }
}
