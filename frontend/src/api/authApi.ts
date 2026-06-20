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

export type LoginResponse = {
    token: string
    tokenType?: string
    user: AuthUser
}

export function login(request: LoginRequest): Promise<LoginResponse> {
    return apiRequest<LoginResponse>('/api/auth/login', {
        method: 'POST',
        auth: false,
        body: JSON.stringify(request),
    })
}

export function getCurrentUser(): Promise<AuthUser> {
    return apiRequest<AuthUser>('/api/auth/me')
}
