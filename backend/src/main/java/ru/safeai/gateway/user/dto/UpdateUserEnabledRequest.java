package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateUserEnabledRequest(
        @NotNull
        Boolean enabled,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
}
