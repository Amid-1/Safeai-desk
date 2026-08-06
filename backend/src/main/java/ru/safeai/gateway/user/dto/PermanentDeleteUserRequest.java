package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PermanentDeleteUserRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String confirmationEmail,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
    public PermanentDeleteUserRequest(String confirmationEmail) {
        this(confirmationEmail, 0L);
    }
}
