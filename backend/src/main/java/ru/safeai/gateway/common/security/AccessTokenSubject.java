package ru.safeai.gateway.common.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Независимый от JPA и domain entities снимок данных для выпуска access JWT.
 */
public record AccessTokenSubject(
        UUID userId,
        UUID organizationId,
        String email,
        long tokenVersion,
        Set<String> roles
) {
    private static final int MAX_EMAIL_LENGTH = 255;

    public AccessTokenSubject {
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(email, "email не должен быть null");
        if (!isCanonicalEmail(email)) {
            throw new IllegalArgumentException(
                    "email должен быть каноническим lowercase email "
                            + "без внешних пробелов и длиной не более 255 символов"
            );
        }

        if (tokenVersion < 0) {
            throw new IllegalArgumentException(
                    "tokenVersion не может быть отрицательным"
            );
        }

        Objects.requireNonNull(roles, "roles не должен быть null");
        List<String> normalizedRoles =
                RoleAuthorityMapper.normalizeRoleNames(roles);

        if (normalizedRoles.isEmpty()) {
            throw new IllegalArgumentException("roles не должен быть пустым");
        }

        roles = java.util.Collections.unmodifiableSet(
                new LinkedHashSet<>(normalizedRoles)
        );
    }

    private static boolean isCanonicalEmail(String email) {
        return !email.isBlank()
                && email.length() <= MAX_EMAIL_LENGTH
                && email.equals(email.trim())
                && email.equals(email.toLowerCase(Locale.ROOT));
    }
}
