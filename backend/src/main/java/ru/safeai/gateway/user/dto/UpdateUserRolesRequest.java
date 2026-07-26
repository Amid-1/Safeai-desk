package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateUserRolesRequest(
        @NotEmpty
        @Size(min = 1, max = 1)
        Set<@NotBlank @Size(max = 100) String> roles
) {
}
