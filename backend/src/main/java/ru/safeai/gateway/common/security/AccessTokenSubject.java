package ru.safeai.gateway.common.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AccessTokenSubject(
        UUID userId,
        UUID organizationId,
        String email,
        long tokenVersion,
        Set<String> roles
) {
    public AccessTokenSubject {
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        if (email == null || email.isBlank() || !email.equals(email.trim())) {
            throw new IllegalArgumentException(
                    "email должен быть непустым и не содержать внешних пробелов"
            );
        }

        if (tokenVersion < 0) {
            throw new IllegalArgumentException(
                    "tokenVersion не может быть отрицательным"
            );
        }

        roles = roles == null ? Set.of() : Set.copyOf(roles);

        if (roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "roles не должен быть пустым"
            );
        }
    }
}
