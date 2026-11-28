package ru.safeai.gateway.auth.dto;

import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record CsrfTokenResponse(
        String headerName,
        String parameterName,
        String token
) {

    public CsrfTokenResponse {
        requireText(headerName, "headerName");
        requireText(parameterName, "parameterName");
        requireText(token, "token");
    }

    @Contract(pure = true)
    @Override
    public @NonNull String toString() {
        return "CsrfTokenResponse[headerName=" + headerName
                + ", parameterName=" + parameterName + ", token=<redacted>]";
    }

    private static void requireText(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName + " не должен быть null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " не должен быть пустым"
            );
        }
    }
}
