package ru.safeai.gateway.common.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Общая валидация и нормализация значений security-модели.
 */
final class SecurityIdentityValidator {

    private static final int MAX_EMAIL_LENGTH = 255;
    private static final String ROLE_PREFIX = "ROLE_";

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "SUPER_ADMIN",
            "ADMIN",
            "USER"
    );

    private SecurityIdentityValidator() {
    }

    static String requireCanonicalEmail(
            String email
    ) {
        String value = Objects.requireNonNull(
                email,
                "email не должен быть null"
        );

        boolean canonical =
                !value.isBlank()
                        && value.length() <= MAX_EMAIL_LENGTH
                        && value.equals(value.strip())
                        && value.equals(
                        value.toLowerCase(Locale.ROOT)
                );

        if (!canonical) {
            throw new IllegalArgumentException(
                    "email должен быть canonical lowercase email; "
                            + "email должен быть каноническим lowercase email"
            );
        }

        return value;
    }

    static long requireNonNegativeVersion(
            long value,
            String fieldName
    ) {
        if (value >= 0) {
            return value;
        }

        String name = Objects.requireNonNull(
                fieldName,
                "fieldName не должен быть null"
        );

        throw new IllegalArgumentException(
                name + " не может быть отрицательным"
        );
    }

    static List<String> normalizeAuthorityNames(
            Collection<? extends GrantedAuthority> authorities
    ) {
        Collection<? extends GrantedAuthority> source =
                Objects.requireNonNull(
                        authorities,
                        "authorities не должны быть null"
                );

        if (source.isEmpty()) {
            throw new IllegalArgumentException(
                    "authorities не должны быть пустыми"
            );
        }

        TreeSet<String> normalizedNames =
                new TreeSet<>();

        for (GrantedAuthority authority : source) {
            GrantedAuthority value =
                    Objects.requireNonNull(
                            authority,
                            "authority не должен быть null"
                    );

            String authorityName =
                    Objects.requireNonNull(
                            value.getAuthority(),
                            "authority name не должен быть null"
                    );

            normalizedNames.add(
                    ROLE_PREFIX
                            + normalizeRoleName(
                            authorityName
                    )
            );
        }

        return List.copyOf(normalizedNames);
    }

    static Set<String> normalizeRoleNames(
            Set<String> roles
    ) {
        Set<String> source = Objects.requireNonNull(
                roles,
                "roles не должен быть null"
        );

        if (source.isEmpty()) {
            throw new IllegalArgumentException(
                    "roles не должен быть пустым"
            );
        }

        TreeSet<String> normalizedNames =
                new TreeSet<>();

        for (String role : source) {
            normalizedNames.add(
                    normalizeRoleName(role)
            );
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(normalizedNames)
        );
    }

    private static String normalizeRoleName(
            String role
    ) {
        String value = Objects.requireNonNull(
                role,
                "role не должен быть null"
        );

        String normalized = value
                .strip()
                .toUpperCase(Locale.ROOT);

        String roleName =
                normalized.startsWith(ROLE_PREFIX)
                        ? normalized.substring(
                        ROLE_PREFIX.length()
                )
                        : normalized;

        if (!ALLOWED_ROLES.contains(roleName)) {
            throw new IllegalArgumentException(
                    "Unknown role: " + value
            );
        }

        return roleName;
    }
}