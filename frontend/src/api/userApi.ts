// ============================================================
// frontend/src/api/userApi.ts
// ============================================================
import { apiRequest } from './http'
import {
    buildQueryString,
    normalizePage,
    normalizePageSize,
    pathSegment,
} from './query'
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
    updatedAt: string
    lastLoginAt: string | null
}

export type UserDetails = User

export type UserStatistics = {
    total: number
    administrators: number
    users: number
    enabled: number
    disabled: number
}

export type UserListRoleFilter = 'ADMIN' | 'USER'

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

export type PermanentDeleteUserRequest = {
    confirmationEmail: string
}

const USER_REQUEST_TIMEOUT_MS = 30_000

function userPath(userId: string, suffix = ''): string {
    return `/api/users/${pathSegment(userId)}${suffix}`
}

function get<T>(url: string): Promise<T> {
    return apiRequest<T>(url, {
        method: 'GET',
        timeoutMs: USER_REQUEST_TIMEOUT_MS,
    })
}

function sendJson<TResponse, TRequest>(
    url: string,
    method: 'POST' | 'PATCH',
    request: TRequest,
): Promise<TResponse> {
    return apiRequest<TResponse>(url, {
        method,
        body: JSON.stringify(request),
        timeoutMs: USER_REQUEST_TIMEOUT_MS,
    })
}

export function getUsers(
    page = 0,
    size = 50,
    role?: UserListRoleFilter,
): Promise<PageResponse<User>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 200),
        role,
    })

    return get<PageResponse<User>>(`/api/users${query}`)
}

export function getUserDetails(userId: string): Promise<UserDetails> {
    return get<UserDetails>(userPath(userId))
}

export function getUserStatistics(): Promise<UserStatistics> {
    return get<UserStatistics>('/api/users/statistics')
}

export function createUser(request: CreateUserRequest): Promise<User> {
    return sendJson<User, CreateUserRequest>(
        '/api/users',
        'POST',
        request,
    )
}

export function updateUser(
    userId: string,
    request: UpdateUserRequest,
): Promise<User> {
    return sendJson<User, UpdateUserRequest>(
        userPath(userId),
        'PATCH',
        request,
    )
}

export function updateUserEnabled(
    userId: string,
    request: UpdateUserEnabledRequest,
): Promise<User> {
    return sendJson<User, UpdateUserEnabledRequest>(
        userPath(userId, '/enabled'),
        'PATCH',
        request,
    )
}

export function updateUserRoles(
    userId: string,
    request: UpdateUserRolesRequest,
): Promise<User> {
    return sendJson<User, UpdateUserRolesRequest>(
        userPath(userId, '/roles'),
        'PATCH',
        request,
    )
}

export function resetUserPassword(
    userId: string,
    request: ResetUserPasswordRequest,
): Promise<void> {
    return sendJson<void, ResetUserPasswordRequest>(
        userPath(userId, '/reset-password'),
        'POST',
        request,
    )
}

export function permanentlyDeleteUser(
    userId: string,
    request: PermanentDeleteUserRequest,
): Promise<void> {
    return sendJson<void, PermanentDeleteUserRequest>(
        userPath(userId, '/permanent-deletion'),
        'POST',
        request,
    )
}