// ============================================================
// frontend/src/api/authApi.ts
// ============================================================

import { apiRequest } from './http'
import type { UserRole } from './types'

const AUTH_BASE_PATH = '/api/auth'
const AUTH_REQUEST_TIMEOUT_MS = 20_000

export type LoginRequest = {
    email: string
    password: string
}

export type AuthUser = {
    id: string
    organizationId: string
    email: string
    fullName: string | null
    enabled: boolean
    roles: UserRole[]
}

export async function login(
    request: LoginRequest,
): Promise<AuthUser> {
    const email = request.email.trim().toLowerCase()

    return apiRequest<AuthUser>(`${AUTH_BASE_PATH}/login`, {
        method: 'POST',
        body: JSON.stringify({
            email,
            password: request.password,
        }),
        timeoutMs: AUTH_REQUEST_TIMEOUT_MS,
    })
}

export function getCurrentUser(): Promise<AuthUser> {
    return apiRequest<AuthUser>(`${AUTH_BASE_PATH}/me`, {
        method: 'GET',
        timeoutMs: AUTH_REQUEST_TIMEOUT_MS,
    })
}

export function logout(): Promise<void> {
    return apiRequest<void>(`${AUTH_BASE_PATH}/logout`, {
        method: 'POST',
        timeoutMs: AUTH_REQUEST_TIMEOUT_MS,
    })
}