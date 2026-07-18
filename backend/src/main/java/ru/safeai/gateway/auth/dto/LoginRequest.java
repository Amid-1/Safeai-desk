package ru.safeai.gateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.user.validation.ValidPassword;

public record LoginRequest(
        @Email
        @NotBlank
        @Size(max = 255)
        String email,

        @ValidPassword
        String password
) {
}
