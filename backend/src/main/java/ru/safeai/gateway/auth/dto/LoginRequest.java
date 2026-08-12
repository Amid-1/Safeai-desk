package ru.safeai.gateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.user.validation.BcryptUtf8Length;

public record LoginRequest(
        @Email
        @NotBlank
        @Size(max = 255)
        String email,

        /*
         * ВАЖНО:
         * login не применяет текущую complexity policy.
         *
         * Старый ранее допустимый пароль должен продолжать
         * использоваться для аутентификации.
         *
         * Здесь проверяется только технический предел BCrypt.
         */
        @NotBlank
        @Size(max = 72)
        @BcryptUtf8Length
        String password
) {
}