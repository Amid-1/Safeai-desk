package ru.safeai.gateway.common.security;

import java.util.Locale;
import java.util.Objects;

public enum SystemRole {
    SUPER_ADMIN,
    ADMIN,
    USER;

    private static final String ROLE_PREFIX = "ROLE_";

    public String roleName() {
        return name();
    }

    public String authority() {
        return ROLE_PREFIX + name();
    }

    public static SystemRole parse(String value) {
        String source = Objects.requireNonNull(
                value,
                "role не должен быть null"
        );

        if (source.isBlank()) {
            throw new IllegalArgumentException(
                    "Role must not be blank"
            );
        }

        String normalized = source
                .strip()
                .toUpperCase(Locale.ROOT);

        if (normalized.startsWith(ROLE_PREFIX)) {
            normalized = normalized.substring(
                    ROLE_PREFIX.length()
            );
        }

        try {
            return SystemRole.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown role: " + value,
                    exception
            );
        }
    }
}
