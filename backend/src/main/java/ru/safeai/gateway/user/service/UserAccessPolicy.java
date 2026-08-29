package ru.safeai.gateway.user.service;

import org.springframework.security.core.GrantedAuthority;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.UserVersionConflictException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.validation.PasswordPolicy;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class UserAccessPolicy {

    private static final Set<SystemRole> LIST_FILTER_ROLES =
            Set.of(SystemRole.USER, SystemRole.ADMIN);

    private final PlatformProperties platformProperties;

    UserAccessPolicy(
            PlatformProperties platformProperties
    ) {
        this.platformProperties = Objects.requireNonNull(
                platformProperties,
                "platformProperties не должен быть null"
        );
    }

    void requireUserManager(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!isAdmin(currentUser)
                && !isSuperAdmin(currentUser)) {
            throw userManagerRequired();
        }
    }

    boolean isAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(currentUser, SystemRole.ADMIN);
    }

    boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(currentUser, SystemRole.SUPER_ADMIN);
    }

    boolean isEnabledAdmin(
            UserEntity user
    ) {
        return user.isEnabled()
                && hasRole(user, SystemRole.ADMIN);
    }

    boolean hasRole(
            UserEntity user,
            SystemRole role
    ) {
        return user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(SystemRole::parse)
                .anyMatch(role::equals);
    }

    UUID resolveTargetOrganizationId(
            UUID requestedOrganizationId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                requestedOrganizationId,
                "requestedOrganizationId не должен быть null"
        );

        if (isSuperAdmin(currentUser)) {
            return requestedOrganizationId;
        }

        if (!currentUser.getOrganizationId()
                .equals(requestedOrganizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя создавать пользователя в другой организации"
            );
        }

        return currentUser.getOrganizationId();
    }

    void rejectPlatformOrganizationCreation(
            UUID targetOrganizationId
    ) {
        if (isPlatformOrganization(targetOrganizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя создавать пользователей "
                            + "в platform organization через "
                            + "обычный user-management endpoint"
            );
        }
    }

    boolean isPlatformOrganization(
            UUID organizationId
    ) {
        return platformProperties
                .organizationId()
                .equals(organizationId);
    }

    void rejectSuperAdminMutation(
            UserEntity user
    ) {
        if (hasRole(user, SystemRole.SUPER_ADMIN)) {
            throw new ForbiddenOperationException(
                    "SUPER_ADMIN нельзя изменять через обычный "
                            + "user-management endpoint"
            );
        }
    }

    void rejectSelfManagement(
            UserEntity user,
            SafeAiUserPrincipal currentUser,
            String operation
    ) {
        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя " + operation
                            + " самого себя через user-management"
            );
        }
    }

    void rejectAdminManagingAdmin(
            UserEntity targetUser,
            SafeAiUserPrincipal currentUser
    ) {
        if (!isSuperAdmin(currentUser)
                && hasRole(targetUser, SystemRole.ADMIN)) {
            throw new ForbiddenOperationException(
                    "ADMIN не может управлять другим ADMIN"
            );
        }
    }

    String normalizeListRole(
            String role
    ) {
        if (role == null || role.isBlank()) {
            return null;
        }

        final SystemRole normalized;
        try {
            normalized = SystemRole.parse(role);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(
                    "Недопустимый фильтр роли: " + role
            );
        }

        if (!LIST_FILTER_ROLES.contains(normalized)) {
            throw new BadRequestException(
                    "Недопустимый фильтр роли: " + role
            );
        }

        return normalized.roleName();
    }

    Set<SystemRole> normalizeRoles(
            Set<String> roles
    ) {
        if (roles == null) {
            return Set.of();
        }

        try {
            return roles.stream()
                    .map(role -> {
                        if (role == null || role.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Пустая системная роль"
                            );
                        }
                        return SystemRole.parse(role.trim());
                    })
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException exception) {
            throw new ConflictException(
                    "Передана неизвестная или пустая системная роль"
            );
        }
    }

    void requireExactlyOneRequestedRole(
            Set<String> roles
    ) {
        if (roles == null || roles.size() != 1) {
            throw new ConflictException(
                    "У пользователя должна быть ровно одна роль"
            );
        }
    }

    void requireExpectedVersion(
            UserEntity user,
            Long expectedVersion
    ) {
        if (expectedVersion == null) {
            throw new BadRequestException(
                    "expectedVersion должен быть указан"
            );
        }

        if (expectedVersion < 0L) {
            throw new BadRequestException(
                    "expectedVersion должен быть неотрицательным числом"
            );
        }

        long actual = user.getVersion();
        if (actual != expectedVersion) {
            throw new UserVersionConflictException(
                    user.getId(),
                    expectedVersion,
                    actual
            );
        }
    }

    String normalizeFullName(
            String fullName
    ) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        String normalized = fullName.trim()
                .replaceAll("\\s+", " ");

        if (normalized.length() > 255) {
            throw new BadRequestException(
                    "Имя не должно превышать 255 символов"
            );
        }

        return normalized;
    }

    String requireValidNewPassword(
            String password
    ) {
        if (!PasswordPolicy.isValidNewPassword(password)) {
            throw new BadRequestException(PasswordPolicy.MESSAGE);
        }
        return password;
    }

    ForbiddenOperationException userManagerRequired() {
        return new ForbiddenOperationException(
                "Только ADMIN или SUPER_ADMIN "
                        + "может управлять пользователями"
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
}
