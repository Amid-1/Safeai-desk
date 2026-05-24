package ru.safeai.gateway.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank
        @Size(max = 255)
        String name
) {
}