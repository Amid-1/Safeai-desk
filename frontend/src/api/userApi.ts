// frontend/src/api/userApi.ts
import { apiRequest } from './http'
import type { PageResponse } from '../utils/page'

export type User = {
    id: string
    organizationId: string
    email: string
    fullName: string | null
    enabled: boolean
    roles: string[]
    createdAt: string
}

export type CreateUserRequest = {
    organizationId: string
    email: string
    password: string
    fullName: string | null
    roles: string[]
}

export type UpdateUserEnabledRequest = {
    enabled: boolean
}

export type UpdateUserRolesRequest = {
    roles: string[]
}

export type ResetUserPasswordRequest = {
    password: string
}

export async function getUsers(
    page = 0,
    size = 50
): Promise<PageResponse<User>> {
    return apiRequest<PageResponse<User>>(`/api/users?page=${page}&size=${size}`)
}

export async function createUser(request: CreateUserRequest): Promise<User> {
    return apiRequest<User>('/api/users', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function updateUserEnabled(
    userId: string,
    request: UpdateUserEnabledRequest
): Promise<User> {
    return apiRequest<User>(`/api/users/${userId}/enabled`, {
        method: 'PATCH',
        body: JSON.stringify(request),
    })
}

export async function updateUserRoles(
    userId: string,
    request: UpdateUserRolesRequest
): Promise<User> {
    return apiRequest<User>(`/api/users/${userId}/roles`, {
        method: 'PATCH',
        body: JSON.stringify(request),
    })
}

export async function resetUserPassword(
    userId: string,
    request: ResetUserPasswordRequest
): Promise<void> {
    return apiRequest<void>(`/api/users/${userId}/reset-password`, {
        method: 'POST',
        body: JSON.stringify(request),
    })
}