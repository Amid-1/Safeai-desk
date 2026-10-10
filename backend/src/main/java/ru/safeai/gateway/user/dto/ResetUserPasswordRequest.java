package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.NonNull;
import ru.safeai.gateway.user.validation.ValidPassword;

public record ResetUserPasswordRequest(
        @NotBlank
        @ValidPassword
        String password,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
    @Override
    public @NonNull String toString() {
        return "ResetUserPasswordRequest[password=<redacted>, "
                + "expectedVersion="
                + expectedVersion
                + "]";
    }
}
