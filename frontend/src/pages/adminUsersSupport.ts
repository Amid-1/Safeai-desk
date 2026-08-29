import { ApiError, getApiErrorMessage } from '../api/http'
import type { User } from '../api/userApi'
import type { AuthUser } from '../api/authApi'
import type { UserRole } from '../api/types'
import type { OrganizationDirectoryItem } from '../api/organizationApi'

export type AssignableRole = Exclude<UserRole, 'SUPER_ADMIN'>

export function getAssignableRole(
    user: User,
): AssignableRole | null {
    const role = user.roles[0]

    if (role === 'USER' || role === 'ADMIN') {
        return role
    }

    return null
}

export function findOrganizationName(
    id: string,
    organizations:
        OrganizationDirectoryItem[],
): string | null {
    return organizations.find(
        (organization) =>
            organization.id === id,
    )?.name ?? null
}

export function getOrganizationName(
    id: string,
    organizations:
        OrganizationDirectoryItem[],
): string {
    return findOrganizationName(
        id,
        organizations,
    ) ?? id
}

export function getRoleLabel(
    role: UserRole,
): string {
    switch (role) {
        case 'SUPER_ADMIN':
            return 'Суперадминистратор'
        case 'ADMIN':
            return 'Администратор'
        case 'USER':
            return 'Пользователь'
    }
}

export function getUnmanageableReason(
    user: User,
    currentUser: AuthUser,
): string {
    if (
        user.roles.includes('SUPER_ADMIN')
    ) {
        return 'Платформенный администратор'
    }

    if (user.id === currentUser.id) {
        return 'Текущий пользователь'
    }

    return 'Недоступно для ADMIN'
}

export function normalizeEmail(
    value: string,
): string {
    return value
        .trim()
        .toLowerCase()
}

export function normalizeOptionalText(
    value: string,
): string | null {
    const normalized = value
        .trim()
        .replace(/\s+/g, ' ')

    return normalized || null
}

export function getMutationErrorMessage(
    error: unknown,
    fallback: string,
): string {
    if (isVersionConflict(error)) {
        return (
            'Пользователь был изменён другим администратором. '
            + 'Список обновлён; повторите решение по свежим данным.'
        )
    }

    return getApiErrorMessage(
        error,
        fallback,
    )
}

export function isVersionConflict(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && (
            error.status === 409
            || error.status === 412
        )
        && (
            error.errorCode
                === 'USER_VERSION_CONFLICT'
            || error.errorCode
                === 'OPTIMISTIC_LOCK_CONFLICT'
        )
}

export function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

