package ru.safeai.gateway.user.service;

import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.repository.RoleRepository;

import java.util.Set;
import java.util.stream.Collectors;

final class UserRoleAssignmentResolver {

    private static final Set<SystemRole> SUPER_ADMIN_ASSIGNABLE_ROLES =
            Set.of(SystemRole.USER, SystemRole.ADMIN);

    private static final Set<SystemRole> ADMIN_ASSIGNABLE_ROLES =
            Set.of(SystemRole.USER);

    private final RoleRepository roleRepository;
    private final UserAccessPolicy accessPolicy;

    UserRoleAssignmentResolver(
            RoleRepository roleRepository,
            UserAccessPolicy accessPolicy
    ) {
        this.roleRepository = roleRepository;
        this.accessPolicy = accessPolicy;
    }

    Set<RoleEntity> resolve(
            Set<String> requestedRoles,
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireExactlyOneRequestedRole(requestedRoles);

        Set<SystemRole> normalizedRoles =
                accessPolicy.normalizeRoles(requestedRoles);

        if (normalizedRoles.size() != 1) {
            throw new ConflictException(
                    "У пользователя должна быть ровно одна роль"
            );
        }

        Set<SystemRole> assignableRoles;
        if (accessPolicy.isSuperAdmin(currentUser)) {
            assignableRoles = SUPER_ADMIN_ASSIGNABLE_ROLES;
        } else if (accessPolicy.isAdmin(currentUser)) {
            assignableRoles = ADMIN_ASSIGNABLE_ROLES;
        } else {
            throw accessPolicy.userManagerRequired();
        }

        if (!assignableRoles.containsAll(normalizedRoles)) {
            throw new ForbiddenOperationException(
                    accessPolicy.isSuperAdmin(currentUser)
                            ? "SUPER_ADMIN нельзя назначать "
                            + "через user-management endpoint"
                            : "ADMIN может назначать только роль USER"
            );
        }

        return normalizedRoles.stream()
                .map(role -> roleRepository
                        .findByName(role.roleName())
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Роль не найдена: "
                                                + role.roleName()
                                )
                        )
                )
                .collect(Collectors.toUnmodifiableSet());
    }
}
