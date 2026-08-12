package ru.safeai.gateway.common.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Полезная нагрузка для выпуска access JWT.
 *
 * <p>organizationAuthVersion является обязательной частью
 * security contract. Перегрузок с неявным значением 0 быть
 * не должно: отсутствие версии должно обнаруживаться компилятором.</p>
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
}