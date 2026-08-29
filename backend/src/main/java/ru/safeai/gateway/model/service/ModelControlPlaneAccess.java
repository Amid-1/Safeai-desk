package ru.safeai.gateway.model.service;

import org.springframework.security.core.GrantedAuthority;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.Objects;

/** Shared role checks for the administrative Model Control Plane. */
final class ModelControlPlaneAccess {

    private static final String SUPER_ADMIN_WRITE_MESSAGE =
            "Только SUPER_ADMIN может изменять model catalog";

    private ModelControlPlaneAccess() {
    }

    static void requireAdminOrSuperAdmin(
            SafeAiUserPrincipal currentUser,
            String publicMessage
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
        Objects.requireNonNull(
                publicMessage,
                "publicMessage не должен быть null"
        );

        boolean allowed = hasAuthority(
                currentUser,
                SystemRole.ADMIN
        ) || hasAuthority(
                currentUser,
                SystemRole.SUPER_ADMIN
        );

        if (!allowed) {
            throw new ForbiddenOperationException(
                    publicMessage
            );
        }
    }

    static void requireSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!hasAuthority(
                currentUser,
                SystemRole.SUPER_ADMIN
        )) {
            throw new ForbiddenOperationException(
                    SUPER_ADMIN_WRITE_MESSAGE
            );
        }
    }

    /**
     * True when the authenticated principal must remain inside its own tenant.
     *
     * <p>Callers invoke this only after their endpoint/service role boundary
     * has already established ADMIN/SUPER_ADMIN access. A SUPER_ADMIN is the
     * only administrative role allowed to cross tenant boundaries.</p>
     */
    static boolean isTenantScopeRestricted(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        return !hasAuthority(
                currentUser,
                SystemRole.SUPER_ADMIN
        );
    }

    private static boolean hasAuthority(
            SafeAiUserPrincipal currentUser,
            SystemRole role
    ) {
        String required = Objects.requireNonNull(
                role,
                "role не должен быть null"
        ).authority();

        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(required::equals);
    }
}
