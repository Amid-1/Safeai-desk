package ru.safeai.gateway.identity.session;

import java.util.UUID;

/**
 * Identity/Tenancy boundary for revoking authenticated sessions after a
 * security-significant mutation. Implementations own the concrete session
 * storage and revocation mechanism.
 */
public interface SessionRevocationPort {

    void revokeAllForUser(
            UUID userId,
            SessionRevocationReason reason
    );

    void revokeAllForOrganization(
            UUID organizationId,
            SessionRevocationReason reason
    );
}
