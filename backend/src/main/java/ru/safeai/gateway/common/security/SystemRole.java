package ru.safeai.gateway.common.security;

import java.util.Locale;
import java.util.Objects;

public enum SystemRole {
    SUPER_ADMIN,
    ADMIN,
    USER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static SystemRole parse(String rawRole) {
        Objects.requireNonNull(rawRole, "Role must not be null");
        if (rawRole.isBlank()) {
            throw new IllegalArgumentException("Role must not be blank");
        }

        String normalized = rawRole.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring("ROLE_".length());
        }

        try {
            return SystemRole.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown role: " + rawRole, exception);
        }
    }
}
