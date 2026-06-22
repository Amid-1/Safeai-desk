// frontend/src/api/authApi.ts
import { apiRequest } from './http'

export type LoginRequest = {
    email: string
    password: string
}

export type AuthUser = {
    id: string
    organizationId: string
    email: string
    fullName?: string | null
    enabled: boolean
    roles: string[]
}

export async function login(request: LoginRequest): Promise<AuthUser> {
    return apiRequest<AuthUser>('/api/auth/login', {
        method: 'POST',
        auth: false,
        body: JSON.stringify(request),
    })
}

export async function getCurrentUser(): Promise<AuthUser> {
    return apiRequest<AuthUser>('/api/auth/me')
}

export async function refresh(): Promise<void> {
    return apiRequest<void>('/api/auth/refresh', {
        method: 'POST',
        auth: false,
    })
}

export async function logout(): Promise<void> {
    return apiRequest<void>('/api/auth/logout', {
        method: 'POST',
        auth: false,
    })
}
