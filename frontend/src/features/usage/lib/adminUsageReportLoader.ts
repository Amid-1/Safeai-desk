// frontend/src/features/usage/lib/adminUsageReportLoader.ts

import {
    getOrganizationUsageDaily,
    getOrganizationUsageModels,
    getOrganizationUsageUsers,
    getUsageByModels,
    getUsageByOrganizations,
    getUsageByOrganization,
    getUsageByUsers,
    getUsageDaily,
    getUsageSummary,
} from '../api/usageApi'
import type {
    UsageDailySummary,
    UsageModelSummary,
    UsageSummary,
    UsageSummaryFilter,
    UsageUserSummary,
    UsageOrganizationSummary,
} from '../api/usageApi'
import {
    normalizePageResponse,
} from '../../../shared/lib/page'
import type {
    UsageReportTab,
} from './adminUsageFilterState'

export const ADMIN_USAGE_PAGE_SIZE = 50

export type UsageReportRows =
    | UsageSummary[]
    | UsageUserSummary[]
    | UsageModelSummary[]
    | UsageDailySummary[]
    | UsageOrganizationSummary[]

export type UsageReportLoadResult = {
    rows: UsageReportRows
    hasNext: boolean
    hasPrevious: boolean
    complete: boolean
}

type LoadUsageReportInput = {
    tab: UsageReportTab
    page: number
    filter: UsageSummaryFilter
    organizationId: string | null
    superAdmin: boolean
    signal: AbortSignal
    pageSize?: number
}

export async function loadUsageReport({
    tab,
    page,
    filter,
    organizationId,
    superAdmin,
    signal,
    pageSize = ADMIN_USAGE_PAGE_SIZE,
}: LoadUsageReportInput): Promise<UsageReportLoadResult> {
    const scopedOrganizationId =
        superAdmin
            ? organizationId
            : null

    if (tab === 'organizations') {
        if (!superAdmin || scopedOrganizationId) throw new Error('Сравнение доступно только для всей платформы.')
        const response = await getUsageByOrganizations(page, pageSize, filter, {signal})
        if (response.page !== page || response.size !== pageSize) throw new Error('Некорректная страница отчёта.')
        return {
            rows: response.content,
            hasNext: page + 1 < response.totalPages,
            hasPrevious: page > 0,
            complete: page === 0 && response.totalPages <= 1,
        }
    }

    if (tab === 'summary') {
        const response = scopedOrganizationId
            ? await getUsageByOrganization(
                scopedOrganizationId,
                page,
                pageSize,
                filter,
                { signal },
            )
            : await getUsageSummary(
                page,
                pageSize,
                filter,
                { signal },
            )

        const normalized = normalizePageResponse(response)

        return {
            rows: normalized.content,
            hasNext:
                page + 1 < normalized.totalPages,
            hasPrevious: page > 0,
            complete: normalized.page === 0 && normalized.totalPages <= 1
                && normalized.totalElements === normalized.content.length,
        }
    }

    if (tab === 'users') {
        const response = scopedOrganizationId
            ? await getOrganizationUsageUsers(
                scopedOrganizationId,
                page,
                pageSize,
                filter,
                { signal },
            )
            : await getUsageByUsers(
                page,
                pageSize,
                filter,
                { signal },
            )

        const normalized = normalizePageResponse(response)

        return {
            rows: normalized.content,
            hasNext:
                page + 1 < normalized.totalPages,
            hasPrevious: page > 0,
            complete: normalized.page === 0 && normalized.totalPages <= 1
                && normalized.totalElements === normalized.content.length,
        }
    }

    if (tab === 'models') {
        const rows = scopedOrganizationId
            ? await getOrganizationUsageModels(
                scopedOrganizationId,
                filter,
                { signal },
            )
            : await getUsageByModels(
                filter,
                { signal },
            )

        return {
            rows,
            hasNext: false,
            hasPrevious: false,
            complete: true,
        }
    }

    const rows = scopedOrganizationId
        ? await getOrganizationUsageDaily(
            scopedOrganizationId,
            filter,
            { signal },
        )
        : await getUsageDaily(
            filter,
            { signal },
        )

    return {
        rows,
        hasNext: false,
        hasPrevious: false,
        complete: true,
    }
}
