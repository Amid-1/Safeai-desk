package ru.safeai.gateway.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
}
