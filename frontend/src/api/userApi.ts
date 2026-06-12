import { apiRequest } from './http'

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

export async function getUsers(): Promise<User[]> {
    return apiRequest<User[]>('/api/users')
}

export async function createUser(request: CreateUserRequest): Promise<User> {
    return apiRequest<User>('/api/users', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}