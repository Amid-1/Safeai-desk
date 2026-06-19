package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetUserPasswordRequest(
        @NotBlank
        @Size(min = 6, max = 72)
        String password
) {
}