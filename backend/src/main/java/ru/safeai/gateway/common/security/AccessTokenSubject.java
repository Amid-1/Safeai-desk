package ru.safeai.gateway.common.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Полезная нагрузка для выпуска access JWT.
 */
public record AccessTokenSubject(
        UUID userId,
        UUID organizationId,
        String email,
        long tokenVersion,
        long organizationAuthVersion,
        Set<String> roles
) {

    public AccessTokenSubject(
            UUID userId,
            UUID organizationId,
            String email,
            long tokenVersion,
            long organizationAuthVersion,
            Set<String> roles
    ) {
        this.userId = Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        this.organizationId = Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        this.email =
                SecurityIdentityValidator
                        .requireCanonicalEmail(email);

        this.tokenVersion =
                SecurityIdentityValidator
                        .requireNonNegativeVersion(
                                tokenVersion,
                                "tokenVersion"
                        );

        this.organizationAuthVersion =
                SecurityIdentityValidator
                        .requireNonNegativeVersion(
                                organizationAuthVersion,
                                "organizationAuthVersion"
                        );

        this.roles =
                SecurityIdentityValidator
                        .normalizeRoleNames(roles);
    }

    /**
     * Совместимый конструктор для старых вызовов.
     */
    public AccessTokenSubject(
            UUID userId,
            UUID organizationId,
            String email,
            long tokenVersion,
            Set<String> roles
    ) {
        this(
                userId,
                organizationId,
                email,
                tokenVersion,
                0L,
                roles
        );
    }
}
