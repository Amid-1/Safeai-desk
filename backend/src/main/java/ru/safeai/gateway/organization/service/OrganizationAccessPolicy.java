package ru.safeai.gateway.organization.service;

import org.springframework.security.core.GrantedAuthority;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.Objects;
import java.util.UUID;

final class OrganizationAccessPolicy {

    private final PlatformProperties platformProperties;

    OrganizationAccessPolicy(
            PlatformProperties platformProperties
    ) {
        this.platformProperties = Objects.requireNonNull(
                platformProperties,
                "platformProperties не должен быть null"
        );
    }

    void requireOrganizationReader(
            SafeAiUserPrincipal currentUser
    ) {
        requirePrincipal(currentUser);

        if (!isAdmin(currentUser)
                && !isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только ADMIN или SUPER_ADMIN "
                            + "может просматривать организации"
            );
        }
    }

    void requireSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        requirePrincipal(currentUser);

        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только SUPER_ADMIN может "
                            + "управлять организациями"
            );
        }
    }

    void requireMutableOrganizationRequest(
            UUID organizationId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                organizationId,
                "id не должен быть null"
        );

        requireSuperAdmin(currentUser);
        rejectPlatformOrganizationMutation(organizationId);
    }

    boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(
                currentUser,
                SystemRole.SUPER_ADMIN
        );
    }

    boolean isPlatformOrganization(
            UUID organizationId
    ) {
        return platformProperties
                .organizationId()
                .equals(organizationId);
    }

    ResourceNotFoundException organizationNotFound(
            UUID id
    ) {
        return new ResourceNotFoundException(
                "Организация не найдена: " + id
        );
    }

    private boolean isAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(
                currentUser,
                SystemRole.ADMIN
        );
    }

    private boolean hasAuthority(
            SafeAiUserPrincipal currentUser,
            SystemRole role
    ) {
        return currentUser
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role.authority()::equals);
    }

    private void rejectPlatformOrganizationMutation(
            UUID organizationId
    ) {
        if (isPlatformOrganization(organizationId)) {
            throw new ForbiddenOperationException(
                    "Платформенную организацию нельзя "
                            + "изменять через обычный "
                            + "organization-management endpoint"
            );
        }
    }

    private static void requirePrincipal(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
    }
}
