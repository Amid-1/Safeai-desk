package ru.safeai.gateway.user.dto;

import ru.safeai.gateway.user.validation.ValidPassword;

public record ResetUserPasswordRequest(
        @ValidPassword
        String password
) {
}
