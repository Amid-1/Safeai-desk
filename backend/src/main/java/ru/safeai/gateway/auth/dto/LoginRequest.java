package ru.safeai.gateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Email
        @NotBlank
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(max = 100)
        String password
) {
}