import { apiRequest } from './http'

export type LoginRequest = {
    email: string
    password: string
}

export type AuthUser = {
    id: string
    organizationId: string
    email: string
    enabled: boolean
    roles: string[]
}

export type LoginResponse = {
    token: string
    user: AuthUser
}

export function login(request: LoginRequest): Promise<LoginResponse> {
    return apiRequest<LoginResponse>('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export function getCurrentUser(): Promise<AuthUser> {
    return apiRequest<AuthUser>('/api/auth/me')
}