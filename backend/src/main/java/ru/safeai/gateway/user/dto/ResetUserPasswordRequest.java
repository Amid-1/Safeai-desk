package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.safeai.gateway.user.validation.ValidPassword;

public record ResetUserPasswordRequest(
        @ValidPassword
        String password,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
    public ResetUserPasswordRequest(String password) {
        this(password, 0L);
    }
}
