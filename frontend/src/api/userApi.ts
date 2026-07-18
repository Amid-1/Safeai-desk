// ============================================================
// frontend/src/api/userApi.ts
// ============================================================

import { apiRequest } from './http'
import { buildQueryString, normalizePage, normalizePageSize, pathSegment } from './query'
import type { UserRole } from './types'
import type { PageResponse } from '../utils/page'

export type User = {
    id: string
    organizationId: string
    email: string
    fullName: string | null
    enabled: boolean
    roles: UserRole[]
    createdAt: string
}

export type CreateUserRequest = {
    organizationId: string
    email: string
    password: string
    fullName: string | null
    roles: Exclude<UserRole, 'SUPER_ADMIN'>[]
}

export type UpdateUserRequest = {
    email: string
    fullName: string | null
}

export type UpdateUserEnabledRequest = {
    enabled: boolean
}

export type UpdateUserRolesRequest = {
    roles: Exclude<UserRole, 'SUPER_ADMIN'>[]
}

export type ResetUserPasswordRequest = {
    password: string
}

export function getUsers(
    page = 0,
    size = 50
): Promise<PageResponse<User>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 200),
    })

    return apiRequest<PageResponse<User>>(
        `/api/users${query}`,
        { timeoutMs: 30_000 }
    )
}

export function createUser(request: CreateUserRequest): Promise<User> {
    return apiRequest<User>('/api/users', {
        method: 'POST',
        body: JSON.stringify(request),
        timeoutMs: 30_000,
    })
}

export function updateUser(
    userId: string,
    request: UpdateUserRequest
): Promise<User> {
    return apiRequest<User>(
        `/api/users/${pathSegment(userId)}`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
            timeoutMs: 30_000,
        }
    )
}

export function updateUserEnabled(
    userId: string,
    request: UpdateUserEnabledRequest
): Promise<User> {
    return apiRequest<User>(
        `/api/users/${pathSegment(userId)}/enabled`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
            timeoutMs: 30_000,
        }
    )
}

export function updateUserRoles(
    userId: string,
    request: UpdateUserRolesRequest
): Promise<User> {
    return apiRequest<User>(
        `/api/users/${pathSegment(userId)}/roles`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
            timeoutMs: 30_000,
        }
    )
}

export function resetUserPassword(
    userId: string,
    request: ResetUserPasswordRequest
): Promise<void> {
    return apiRequest<void>(
        `/api/users/${pathSegment(userId)}/reset-password`,
        {
            method: 'POST',
            body: JSON.stringify(request),
            timeoutMs: 30_000,
        }
    )
}

