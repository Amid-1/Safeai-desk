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
 * Общие security invariants для principal и JWT subject.
 *
 * <p>Класс намеренно package-private: это внутренняя
 * security validation API общего security package.</p>
 */
final class SecurityIdentityValidator {

    private static final int MAX_EMAIL_LENGTH =
            255;

    private SecurityIdentityValidator() {
    }

    static String requireCanonicalEmail(
            String email
    ) {
        String value =
                Objects.requireNonNull(
                        email,
                        "email не должен быть null"
                );

        if (value.isBlank()) {
            throw invalidEmail();
        }

        if (value.length()
                > MAX_EMAIL_LENGTH) {

            throw invalidEmail();
        }

        if (!value.equals(
                value.strip()
        )) {
            throw invalidEmail();
        }

        if (!value.equals(
                value.toLowerCase(
                        Locale.ROOT
                )
        )) {
            throw invalidEmail();
        }

        return value;
    }

    static long requireNonNegativeVersion(
            long value,
            String fieldName
    ) {
        String name =
                Objects.requireNonNull(
                        fieldName,
                        "fieldName не должен быть null"
                );

        if (value < 0L) {
            throw new IllegalArgumentException(
                    name
                            + " не может быть отрицательным"
            );
        }

        return value;
    }

    static List<String> normalizeAuthorityNames(
            Collection<? extends GrantedAuthority> authorities
    ) {
        Objects.requireNonNull(
                authorities,
                "authorities не должен быть null"
        );

        if (authorities.isEmpty()) {
            throw new IllegalArgumentException(
                    "authorities не должен быть пустым"
            );
        }

        TreeSet<String> normalizedNames =
                new TreeSet<>();

        for (GrantedAuthority authority
                : authorities) {

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
                    SystemRole
                            .parse(
                                    authorityName
                            )
                            .authority()
            );
        }

        /*
         * Дополнительная normalizedNames.isEmpty()
         * здесь не требуется:
         *
         * authorities уже гарантированно non-empty;
         * каждый элемент либо добавляет canonical role,
         * либо validation выбрасывает exception.
         */
        return List.copyOf(
                normalizedNames
        );
    }

    static Set<String> normalizeRoleNames(
            Set<String> roles
    ) {
        Objects.requireNonNull(
                roles,
                "roles не должен быть null"
        );

        if (roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "roles не должен быть пустым"
            );
        }

        TreeSet<String> normalizedNames =
                new TreeSet<>();

        for (String role : roles) {
            normalizedNames.add(
                    SystemRole
                            .parse(role)
                            .roleName()
            );
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        normalizedNames
                )
        );
    }

    private static IllegalArgumentException invalidEmail() {
        return new IllegalArgumentException(
                "email должен быть каноническим lowercase email"
        );
    }
}