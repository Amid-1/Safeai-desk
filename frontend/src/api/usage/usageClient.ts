// ============================================================
// frontend/src/api/usage/usageClient.ts
// ============================================================

import { API_TIMEOUTS, apiRequest } from '../http'
import { buildQueryString, normalizePage, normalizePageSize, uuidPathSegment } from '../query'
import { contractError, expectRecord, parsePageResponse } from '../runtime'
import type { PageResponse } from '../../utils/page'
import type { UsageSummaryFilter, RequestOptions, UsageSummary, UsageUserSummary, UsageModelSummary, UsageDailySummary } from './usageTypes'
import { parseUsageSummary, parseUsageUserSummary, parseUsageModelSummary, parseUsageDailySummary } from './usageParsers'

export function getUsageSummary(
    page = 0,
    size = 50,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        '/api/admin/usage/summary',
        page,
        size,
        filter,
        parseUsageSummary,
        options,
    )
}

export function getUsageByUsers(
    page = 0,
    size = 50,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        '/api/admin/usage/users',
        page,
        size,
        filter,
        parseUsageUserSummary,
        options,
    )
}

export function getUsageByOrganization(
    organizationId: string,
    page = 0,
    size = 50,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/by-organization/${uuidPathSegment(organizationId)}`,
        page,
        size,
        filter,
        parseUsageSummary,
        options,
    )
}

export function getOrganizationUsageUsers(
    organizationId: string,
    page = 0,
    size = 50,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        `/api/admin/usage/by-organization/${uuidPathSegment(organizationId)}/users`,
        page,
        size,
        filter,
        parseUsageUserSummary,
        options,
    )
}

export function getUsageByModels(
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        '/api/admin/usage/models',
        filter,
        parseUsageModelSummary,
        options,
    )
}

export function getOrganizationUsageModels(
    organizationId: string,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        `/api/admin/usage/by-organization/${uuidPathSegment(organizationId)}/models`,
        filter,
        parseUsageModelSummary,
        options,
    )
}

export function getUsageDaily(
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        '/api/admin/usage/daily',
        filter,
        parseUsageDailySummary,
        options,
    )
}

export function getOrganizationUsageDaily(
    organizationId: string,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        `/api/admin/usage/by-organization/${uuidPathSegment(organizationId)}/daily`,
        filter,
        parseUsageDailySummary,
        options,
    )
}

async function getUsagePage<T>(
    path: string,
    page: number,
    size: number,
    filter: UsageSummaryFilter,
    parser: (value: unknown, field?: string) => T,
    options: RequestOptions,
): Promise<PageResponse<T>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 100),
        dateFrom: normalizeOptionalString(filter.dateFrom),
        dateTo: normalizeOptionalString(filter.dateTo),
        model: normalizeOptionalString(filter.model),
    })

    const response = await apiRequest<unknown>(
        `${path}${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parseUsagePageResponse(response, parser)
}

export function parseUsagePageResponse<T>(
    value: unknown,
    parser: (value: unknown, field?: string) => T,
): PageResponse<T> {
    const record = expectRecord(value, 'usagePage')

    // Отчёты используют Slice, чтобы не выполнять тяжёлый COUNT-запрос.
    // Для общего компонента пагинации создаются только ориентировочные границы;
    // точное количество не объявляется известным. Старый Page тоже поддерживается.
    if (
        Array.isArray(record.content)
        && typeof record.hasNext === 'boolean'
        && typeof record.hasPrevious === 'boolean'
        && typeof record.page === 'number'
        && typeof record.size === 'number'
        && record.totalElements === undefined
    ) {
        // Метаданные Slice пришли от сервера: не исправляем их молча.
        if (!Number.isSafeInteger(record.page) || record.page < 0
            || !Number.isSafeInteger(record.size) || record.size < 1 || record.size > 100
            || record.hasPrevious !== (record.page > 0)
            || (record.first !== undefined && record.first !== (record.page === 0))
            || (record.last !== undefined && record.last !== !record.hasNext)
            || record.content.length > record.size) {
            throw contractError('usagePage: некорректные метаданные Slice')
        }
        const page = record.page
        const size = record.size
        const content = record.content.map(
            (item, index) => parser(
                item,
                `usagePage.content[${index}]`,
            ),
        )
        const totalPages = record.hasNext
            ? page + 2
            : page + (content.length > 0 ? 1 : 0)

        return {
            content,
            page,
            size,
            totalPages,
            totalElements: page * size + content.length
                + (record.hasNext ? 1 : 0),
        }
    }

    return parsePageResponse(
        value,
        (item, field) => parser(item, field),
    )
}

async function getUsageArray<T>(
    path: string,
    filter: UsageSummaryFilter,
    parser: (value: unknown, field?: string) => T,
    options: RequestOptions,
): Promise<T[]> {
    const query = buildQueryString({
        dateFrom: normalizeOptionalString(filter.dateFrom),
        dateTo: normalizeOptionalString(filter.dateTo),
        model: normalizeOptionalString(filter.model),
    })

    const response = await apiRequest<unknown>(
        `${path}${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    if (!Array.isArray(response)) {
        throw contractError(
            'usage response должен быть массивом',
        )
    }

    return response.map(
        (value, index) =>
            parser(
                value,
                `usage[${index}]`,
            ),
    )
}

function normalizeOptionalString(
    value: string | undefined,
): string | undefined {
    const normalized = value?.trim()
    return normalized || undefined
}
