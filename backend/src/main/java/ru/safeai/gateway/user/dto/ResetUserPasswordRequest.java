package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.user.validation.PasswordPolicy;

public record ResetUserPasswordRequest(
        @NotBlank
        @Size(min = 12, max = 72)
        @Pattern(
                regexp = PasswordPolicy.REGEX,
                message = PasswordPolicy.MESSAGE
        )
        String password
) {
}