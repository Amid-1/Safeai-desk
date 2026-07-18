// ============================================================
// frontend/src/api/adminApi.ts
// ============================================================

import { apiRequest } from './http'
import {
    buildQueryString,
    normalizePage,
    normalizePageSize,
    pathSegment,
} from './query'
import type { PageResponse } from '../utils/page'

const ADMIN_REQUEST_TIMEOUT_MS = 30_000

export type AuditEvent = {
    id: string
    organizationId: string

    /**
     * Исторический snapshot пользователя на момент события.
     */
    userId: string | null
    userEmail: string | null
    userDisplayName: string | null

    eventType: string
    details: Record<string, unknown>
    createdAt: string
}

export type AuditEventFilter = {
    eventType?: string
    userId?: string
    userEmail?: string
    dateFrom?: string
    dateTo?: string
    organizationId?: string
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

export type UsageFilter = {
    dateFrom?: string
    dateTo?: string
    model?: string
}

type UsageDateRangeFilter = Pick<
    UsageFilter,
    'dateFrom' | 'dateTo'
>

export function getAuditEvents(
    page = 0,
    size = 50,
    filter: AuditEventFilter = {},
): Promise<PageResponse<AuditEvent>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 100),
        eventType: normalizeOptionalString(filter.eventType),
        userId: normalizeOptionalString(filter.userId),
        userEmail: normalizeOptionalString(filter.userEmail),
        dateFrom: normalizeOptionalString(filter.dateFrom),
        dateTo: normalizeOptionalString(filter.dateTo),
        organizationId: normalizeOptionalString(
            filter.organizationId,
        ),
    })

    return apiRequest<PageResponse<AuditEvent>>(
        `/api/admin/audit-events${query}`,
        {
            method: 'GET',
            timeoutMs: ADMIN_REQUEST_TIMEOUT_MS,
        },
    )
}

export async function getUsageSummary(
    filter: UsageFilter = {},
): Promise<UsageSummary[]> {
    return getUsagePageContent<UsageSummary>(
        `/api/admin/usage/summary${usageQuery(filter)}`,
    )
}

export async function getUsageByUsers(
    filter: UsageDateRangeFilter = {},
): Promise<UsageUserSummary[]> {
    return getUsagePageContent<UsageUserSummary>(
        `/api/admin/usage/users${usageQuery(filter)}`,
    )
}

export async function getUsageByModels(
    filter: UsageDateRangeFilter = {},
): Promise<UsageModelSummary[]> {
    return getUsagePageContent<UsageModelSummary>(
        `/api/admin/usage/models${usageQuery(filter)}`,
    )
}

export async function getUsageDaily(
    filter: UsageDateRangeFilter = {},
): Promise<UsageDailySummary[]> {
    return getUsagePageContent<UsageDailySummary>(
        `/api/admin/usage/daily${usageQuery(filter)}`,
    )
}

export async function getUsageByOrganization(
    organizationId: string,
    filter: UsageFilter = {},
): Promise<UsageSummary[]> {
    return getUsagePageContent<UsageSummary>(
        `/api/admin/usage/by-organization/${pathSegment(
            organizationId,
        )}${usageQuery(filter)}`,
    )
}

async function getUsagePageContent<T>(
    url: string,
): Promise<T[]> {
    const response = await apiRequest<PageResponse<T>>(url, {
        method: 'GET',
        timeoutMs: ADMIN_REQUEST_TIMEOUT_MS,
    })

    if (!Array.isArray(response.content)) {
        throw new TypeError(
            'Некорректный ответ usage API: поле content не является массивом.',
        )
    }

    return response.content
}

function usageQuery(
    filter: UsageFilter | UsageDateRangeFilter,
): string {
    return buildQueryString({
        dateFrom: normalizeOptionalString(filter.dateFrom),
        dateTo: normalizeOptionalString(filter.dateTo),
        model:
            'model' in filter
                ? normalizeOptionalString(filter.model)
                : undefined,
    })
}

function normalizeOptionalString(
    value: string | undefined,
): string | undefined {
    const normalized = value?.trim()

    return normalized || undefined
}