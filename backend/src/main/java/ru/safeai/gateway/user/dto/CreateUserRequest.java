package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.user.validation.ValidPassword;

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
        @ValidPassword
        String password,

        @Size(max = 255)
        String fullName,

        @NotNull
        @Size(min = 1, max = 1)
        Set<
                @NotBlank
                @Size(max = 100)
                String
                > roles
) {
}