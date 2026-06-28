// frontend/src/api/adminApi.ts
import { apiRequest } from './http'
import type { PageResponse } from '../utils/page'

export type AuditEvent = {
    id: string
    organizationId: string | null
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

export type UsageUserSummary = {
    userId: string
    userEmail: string
    inputTokens: number
    outputTokens: number
    totalTokens: number
    costUsd: number
}

export type UsageModelSummary = {
    model: string
    inputTokens: number
    outputTokens: number
    totalTokens: number
    costUsd: number
}

export type UsageDailySummary = {
    usageDate: string
    inputTokens: number
    outputTokens: number
    totalTokens: number
    costUsd: number
}

export type AuditEventFilter = {
    eventType?: string
    userEmail?: string
    dateFrom?: string
    dateTo?: string
    organizationId?: string
}

export function getAuditEvents(
    page = 0,
    size = 50,
    filter: AuditEventFilter = {}
): Promise<PageResponse<AuditEvent>> {
    const params = new URLSearchParams()

    params.set('page', String(page))
    params.set('size', String(size))

    if (filter.eventType) {
        params.set('eventType', filter.eventType)
    }

    if (filter.userEmail) {
        params.set('userEmail', filter.userEmail)
    }

    if (filter.dateFrom) {
        params.set('dateFrom', filter.dateFrom)
    }

    if (filter.dateTo) {
        params.set('dateTo', filter.dateTo)
    }

    if (filter.organizationId) {
        params.set('organizationId', filter.organizationId)
    }

    return apiRequest<PageResponse<AuditEvent>>(
        `/api/admin/audit-events?${params.toString()}`
    )
}

export function getUsageSummary(): Promise<UsageSummary[]> {
    return apiRequest<UsageSummary[]>('/api/admin/usage/summary')
}

export function getUsageByUsers(): Promise<UsageUserSummary[]> {
    return apiRequest<UsageUserSummary[]>('/api/admin/usage/users')
}

export function getUsageByModels(): Promise<UsageModelSummary[]> {
    return apiRequest<UsageModelSummary[]>('/api/admin/usage/models')
}

export function getUsageDaily(): Promise<UsageDailySummary[]> {
    return apiRequest<UsageDailySummary[]>('/api/admin/usage/daily')
}