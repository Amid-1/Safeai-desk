package ru.safeai.gateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.auth.validation.Utf8ByteLength;

public record LoginRequest(
        @Email
        @NotBlank
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(max = 72)
        @Utf8ByteLength(
                max = 72,
                message = "Пароль не должен превышать 72 байта в UTF-8"
        )
        String password
) {
}
