package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUserEnabledRequest(
        @NotNull
        Boolean enabled
) {
}