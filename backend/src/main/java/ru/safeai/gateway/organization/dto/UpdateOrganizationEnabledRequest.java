package ru.safeai.gateway.organization.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateOrganizationEnabledRequest(
        @NotNull
        Boolean enabled
) {
}