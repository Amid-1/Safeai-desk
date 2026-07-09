// frontend/src/api/adminApi.ts
import { apiRequest } from './http'
import type { PageResponse } from '../utils/page'

export type AuditEvent = {
    id: string
    organizationId: string | null
    organizationName?: string | null
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

export type UsageFilter = {
    dateFrom?: string
    dateTo?: string
    model?: string
}

type UsageDateRangeFilter = Pick<UsageFilter, 'dateFrom' | 'dateTo'>

function buildQueryParams(values: Record<string, string | number | undefined>): string {
    const params = new URLSearchParams()

    Object.entries(values).forEach(([key, value]) => {
        if (value !== undefined && value !== '') {
            params.set(key, String(value))
        }
    })

    const query = params.toString()

    return query ? `?${query}` : ''
}

export function getAuditEvents(
    page = 0,
    size = 50,
    filter: AuditEventFilter = {}
): Promise<PageResponse<AuditEvent>> {
    const query = buildQueryParams({
        page,
        size,
        eventType: filter.eventType,
        userEmail: filter.userEmail,
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
        organizationId: filter.organizationId,
    })

    return apiRequest<PageResponse<AuditEvent>>(
        `/api/admin/audit-events${query}`
    )
}

export function getUsageSummary(
    filter: UsageFilter = {}
): Promise<UsageSummary[]> {
    const query = buildQueryParams({
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
        model: filter.model,
    })

    return apiRequest<UsageSummary[]>(`/api/admin/usage/summary${query}`)
}

export function getUsageByUsers(
    filter: UsageDateRangeFilter = {}
): Promise<UsageUserSummary[]> {
    const query = buildQueryParams({
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
    })

    return apiRequest<UsageUserSummary[]>(`/api/admin/usage/users${query}`)
}

export function getUsageByModels(
    filter: UsageDateRangeFilter = {}
): Promise<UsageModelSummary[]> {
    const query = buildQueryParams({
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
    })

    return apiRequest<UsageModelSummary[]>(`/api/admin/usage/models${query}`)
}

export function getUsageDaily(
    filter: UsageDateRangeFilter = {}
): Promise<UsageDailySummary[]> {
    const query = buildQueryParams({
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
    })

    return apiRequest<UsageDailySummary[]>(`/api/admin/usage/daily${query}`)
}