import { apiRequest } from './http'

export type AuditEvent = {
    id: string
    userId: string | null
    userEmail: string | null
    eventType: string
    details: Record<string, unknown> | null
    createdAt: string
}

export type UsageSummary = {
    userId: string
    userEmail: string
    model: string
    inputTokens: number
    outputTokens: number
    totalTokens: number
    costUsd: number
}

export async function getAuditEvents(): Promise<AuditEvent[]> {
    return apiRequest<AuditEvent[]>('/api/admin/audit-events')
}

export async function getUsageSummary(): Promise<UsageSummary[]> {
    return apiRequest<UsageSummary[]>('/api/admin/usage/summary')
}