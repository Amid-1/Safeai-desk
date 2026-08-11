
package ru.safeai.gateway.user.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserDetailsResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        String email,
        String fullName,
        boolean enabled,
        Set<String> roles,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt
) {
    public UserDetailsResponse {
        Objects.requireNonNull(
                id,
                "id не должен быть null"
        );
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );
        Objects.requireNonNull(
                email,
                "email не должен быть null"
        );
        Objects.requireNonNull(
                roles,
                "roles не должен быть null"
        );
        Objects.requireNonNull(
                createdAt,
                "createdAt не должен быть null"
        );
        Objects.requireNonNull(
                updatedAt,
                "updatedAt не должен быть null"
        );

        organizationName =
                normalizeOrganizationName(
                        organizationName
                );

        roles = Set.copyOf(roles);

        if (version < 0L) {
            throw new IllegalArgumentException(
                    "version не может быть отрицательной"
            );
        }
    }

    /**
     * Совместимость с внутренними тестами/старыми вызовами,
     * в которых version уже передавался явно, но названия
     * организации в контракте ещё не было.

     * Production service должен использовать основной
     * конструктор и всегда передавать organizationName.
     */
    public UserDetailsResponse(
            UUID id,
            UUID organizationId,
            String email,
            String fullName,
            boolean enabled,
            Set<String> roles,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt
    ) {
        this(
                id,
                organizationId,
                null,
                email,
                fullName,
                enabled,
                roles,
                version,
                createdAt,
                updatedAt,
                lastLoginAt
        );
    }

    private static String normalizeOrganizationName(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
