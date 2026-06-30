package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.user.validation.PasswordPolicy;

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
        @Pattern(
                regexp = PasswordPolicy.REGEX,
                message = PasswordPolicy.MESSAGE
        )
        String password,

        @Size(max = 255)
        String fullName,

        @Size(max = 2)
        Set<@NotBlank @Size(max = 100) String> roles
) {
}