package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetUserPasswordRequest(
        @NotBlank
        @Size(min = 12, max = 72)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,72}$",
                message = "Пароль должен содержать минимум 12 символов, строчную букву, заглавную букву, цифру и спецсимвол"
        )
        String password
) {
}