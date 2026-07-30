package ru.safeai.gateway.audit.model;

import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record AuditActor(
        UUID userId,
        UUID organizationId,
        String email,
        String displayName
) {
    private static final int MAX_IDENTITY_LENGTH = 255;

    public AuditActor {
        email = normalizeEmail(email);
        displayName = normalize(displayName);

        if (userId != null) {
            Objects.requireNonNull(
                    organizationId,
                    "organizationId actor-а не должен быть null"
            );
        }

        validateLength(email, "email");
        validateLength(displayName, "displayName");
    }

    public static AuditActor fromPrincipal(
            SafeAiUserPrincipal principal
    ) {
        return fromPrincipal(principal, null);
    }

    public static AuditActor fromPrincipal(
            SafeAiUserPrincipal principal,
            String displayName
    ) {
        Objects.requireNonNull(
                principal,
                "principal не должен быть null"
        );

        return new AuditActor(
                principal.getId(),
                principal.getOrganizationId(),
                principal.getEmail(),
                displayName
        );
    }

    public static AuditActor system() {
        return new AuditActor(
                null,
                null,
                null,
                "SYSTEM"
        );
    }

    private static String normalizeEmail(String value) {
        String normalized = normalize(value);

        return normalized == null
                ? null
                : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private static void validateLength(
            String value,
            String field
    ) {
        if (value != null
                && value.length() > MAX_IDENTITY_LENGTH) {
            throw new IllegalArgumentException(
                    field + " должен быть не длиннее "
                            + MAX_IDENTITY_LENGTH
                            + " символов"
            );
        }
    }
}
