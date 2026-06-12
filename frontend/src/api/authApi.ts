import { apiRequest } from './http'

export type AuthUser = {
    id: string
    organizationId: string
    email: string
    enabled: boolean
    roles: string[]
}

export type LoginResponse = {
    token: string
    tokenType: string
    user: AuthUser
}

export async function login(email: string, password: string): Promise<LoginResponse> {
    return apiRequest<LoginResponse>('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
    })
}

export async function getMe(): Promise<AuthUser> {
    return apiRequest<AuthUser>('/api/auth/me')
}