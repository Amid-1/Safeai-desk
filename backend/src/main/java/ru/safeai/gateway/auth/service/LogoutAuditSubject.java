package ru.safeai.gateway.auth.service;

import java.util.Objects;
import java.util.UUID;

public record LogoutAuditSubject(
        UUID userId,
        UUID organizationId,
        String email
) {

    public LogoutAuditSubject {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                email,
                "email не должен быть null"
        );

        if (email.isBlank()) {
            throw new IllegalArgumentException(
                    "email не должен быть пустым"
            );
        }
    }
}