// frontend/src/features/usage/api/adminUsage/adminUsageClient.ts
// ============================================================
// frontend/src/features/usage/api/adminUsage/adminUsageClient.ts
// ============================================================

import { API_TIMEOUTS, apiRequest } from '../../../../shared/api/http'
import { buildQueryString, normalizePage, normalizePageSize, uuidPathSegment } from '../../../../shared/api/query'
import { contractError, parsePageResponse } from '../../../../shared/api/runtime'
import type { PageResponse } from '../../../../shared/lib/page'
import type { RequestOptions, UsageFilter, UsageDateRangeFilter, UsageSummary, UsageUserSummary, UsageModelSummary, UsageDailySummary } from './adminUsageTypes'
import { parseUsageSummary, parseUsageUserSummary, parseUsageModelSummary, parseUsageDailySummary } from './adminUsageParsers'

function normalizeOptionalString(
    value: string | undefined,
): string | undefined {
    const normalized = value?.trim()
    return normalized || undefined
}

export async function getUsageSummary(
    page: number,
    size: number,
    filter: UsageFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/summary${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageSummary,
        options,
    )
}

export async function getUsageByUsers(
    page: number,
    size: number,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        `/api/admin/usage/users${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageUserSummary,
        options,
    )
}

export async function getUsageByUser(
    userId: string,
    page: number,
    size: number,
    filter: UsageFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/by-user/${uuidPathSegment(
            userId,
        )}${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageSummary,
        options,
    )
}

export async function getUsageByOrganization(
    organizationId: string,
    page: number,
    size: number,
    filter: UsageFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/by-organization/${uuidPathSegment(
            organizationId,
        )}${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageSummary,
        options,
    )
}

export async function getUsageByModels(
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        `/api/admin/usage/models${usageQuery(
            filter,
        )}`,
        parseUsageModelSummary,
        options,
    )
}

export async function getUsageDaily(
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        `/api/admin/usage/daily${usageQuery(
            filter,
        )}`,
        parseUsageDailySummary,
        options,
    )
}

async function getUsagePage<T>(
    url: string,
    parser: (
        value: unknown,
        field: string,
    ) => T,
    options: RequestOptions,
): Promise<PageResponse<T>> {
    const response = await apiRequest<unknown>(
        url,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parsePageResponse(
        response,
        parser,
    )
}

async function getUsageArray<T>(
    url: string,
    parser: (
        value: unknown,
        field: string,
    ) => T,
    options: RequestOptions,
): Promise<T[]> {
    const response = await apiRequest<unknown>(
        url,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parseArray(
        response,
        parser,
        'usageResponse',
    )
}

function parseArray<T>(
    value: unknown,
    parser: (
        value: unknown,
        field: string,
    ) => T,
    field: string,
): T[] {
    if (!Array.isArray(value)) {
        throw contractError(
            `${field} должен быть массивом`,
        )
    }

    return value.map(
        (item, index) =>
            parser(
                item,
                `${field}[${index}]`,
            ),
    )
}

function usagePagedQuery(
    page: number,
    size: number,
    filter:
        | UsageFilter
        | UsageDateRangeFilter,
): string {
    return buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            200,
        ),
        ...usageQueryValues(filter),
    })
}

function usageQuery(
    filter:
        | UsageFilter
        | UsageDateRangeFilter,
): string {
    return buildQueryString(
        usageQueryValues(filter),
    )
}

function usageQueryValues(
    filter:
        | UsageFilter
        | UsageDateRangeFilter,
): Record<
    string,
    string | undefined
> {
    return {
        dateFrom:
            normalizeOptionalString(
                filter.dateFrom,
            ),
        dateTo:
            normalizeOptionalString(
                filter.dateTo,
            ),
        model:
            'model' in filter
                ? normalizeOptionalString(
                    filter.model,
                )
                : undefined,
    }
}
