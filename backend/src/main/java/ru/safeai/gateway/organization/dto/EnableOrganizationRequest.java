package ru.safeai.gateway.organization.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record EnableOrganizationRequest(
        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
}
