package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreateUserRequest(
        @NotNull
        UUID organizationId,

        @Email
        @NotBlank
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 12, max = 72)
        String password,

        @Size(max = 255)
        String fullName,

        @Size(max = 2)
        Set<@NotBlank @Size(max = 100) String> roles
) {
}