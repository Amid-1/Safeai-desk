package ru.safeai.gateway.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DisableOrganizationRequest(
        @NotNull
        @PositiveOrZero
        Long expectedVersion,

        @NotBlank
        @Size(max = 255)
        String confirmationName
) {
}
