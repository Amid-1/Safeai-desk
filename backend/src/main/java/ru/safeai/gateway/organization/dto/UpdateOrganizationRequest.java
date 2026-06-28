package ru.safeai.gateway.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
        @NotBlank
        @Size(max = 255)
        String name
) {
}