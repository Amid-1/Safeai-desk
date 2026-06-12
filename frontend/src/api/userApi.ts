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

export async function getUsers(): Promise<User[]> {
    return apiRequest<User[]>('/api/users')
}