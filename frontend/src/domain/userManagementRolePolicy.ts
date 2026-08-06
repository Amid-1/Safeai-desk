// ============================================================
// frontend/src/domain/userManagementRolePolicy.ts
// ============================================================
import type { UserRole } from '../api/types'

export type ManagedUserRole = Exclude<
    UserRole,
    'SUPER_ADMIN'
>

export type UserManagementRolePolicy = {
    canManageUsers: boolean
    canChooseRole: boolean
    defaultRole: ManagedUserRole
    assignableRoles:
        readonly ManagedUserRole[]
}

const USER_ONLY_POLICY:
UserManagementRolePolicy = {
    canManageUsers: true,
    canChooseRole: false,
    defaultRole: 'USER',
    assignableRoles: ['USER'],
}

const SUPER_ADMIN_POLICY:
UserManagementRolePolicy = {
    canManageUsers: true,
    canChooseRole: true,
    defaultRole: 'USER',
    assignableRoles: [
        'USER',
        'ADMIN',
    ],
}

const FORBIDDEN_POLICY:
UserManagementRolePolicy = {
    canManageUsers: false,
    canChooseRole: false,
    defaultRole: 'USER',
    assignableRoles: [],
}

export function getUserManagementRolePolicy(
    actorRoles: readonly UserRole[],
): UserManagementRolePolicy {
    if (
        actorRoles.includes(
            'SUPER_ADMIN',
        )
    ) {
        return SUPER_ADMIN_POLICY
    }

    if (actorRoles.includes('ADMIN')) {
        return USER_ONLY_POLICY
    }

    return FORBIDDEN_POLICY
}

export function resolveManagedUserRole(
    actorRoles: readonly UserRole[],
    requestedRole: ManagedUserRole,
): ManagedUserRole {
    const policy =
        getUserManagementRolePolicy(
            actorRoles,
        )

    if (!policy.canManageUsers) {
        throw new Error(
            'Текущая роль не даёт права управлять пользователями.',
        )
    }

    return policy.assignableRoles.includes(
        requestedRole,
    )
        ? requestedRole
        : policy.defaultRole
}
