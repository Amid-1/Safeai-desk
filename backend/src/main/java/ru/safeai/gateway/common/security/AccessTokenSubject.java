package ru.safeai.gateway.common.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Минимальная security-нагрузка для выпуска access JWT.
 *
 * <p>PII вроде email намеренно не включается: access token содержит только
 * идентификаторы и данные, необходимые для авторизации и немедленной
 * инвалидизации security state.</p>
 *
 * <p>Security invariant: subject содержит ровно одну системную роль.</p>
 */
public record AccessTokenSubject(
        UUID userId,
        UUID organizationId,
        long tokenVersion,
        long organizationAuthVersion,
        Set<String> roles
) {

    public AccessTokenSubject(
            UUID userId,
            UUID organizationId,
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

        this.tokenVersion =
                SecurityIdentityValidator.requireNonNegativeVersion(
                        tokenVersion,
                        "tokenVersion"
                );

        this.organizationAuthVersion =
                SecurityIdentityValidator.requireNonNegativeVersion(
                        organizationAuthVersion,
                        "organizationAuthVersion"
                );

        this.roles = SecurityIdentityValidator.normalizeRoleNames(roles);
    }
}
