// frontend/src/pages/adminUsageReportLoader.ts

import {
    getOrganizationUsageDaily,
    getOrganizationUsageModels,
    getOrganizationUsageUsers,
    getUsageByModels,
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
} from '../api/usageApi'
import {
    normalizePageResponse,
} from '../utils/page'
import type {
    UsageReportTab,
} from './adminUsageFilterState'

export const ADMIN_USAGE_PAGE_SIZE = 50

export type UsageReportRows =
    | UsageSummary[]
    | UsageUserSummary[]
    | UsageModelSummary[]
    | UsageDailySummary[]

export type UsageReportLoadResult = {
    rows: UsageReportRows
    hasNext: boolean
    hasPrevious: boolean
}

type LoadUsageReportInput = {
    tab: UsageReportTab
    page: number
    filter: UsageSummaryFilter
    organizationId: string | null
    superAdmin: boolean
    signal: AbortSignal
}

export async function loadUsageReport({
    tab,
    page,
    filter,
    organizationId,
    superAdmin,
    signal,
}: LoadUsageReportInput): Promise<UsageReportLoadResult> {
    const scopedOrganizationId =
        superAdmin
            ? organizationId
            : null

    if (tab === 'summary') {
        const response = scopedOrganizationId
            ? await getUsageByOrganization(
                scopedOrganizationId,
                page,
                ADMIN_USAGE_PAGE_SIZE,
                filter,
                { signal },
            )
            : await getUsageSummary(
                page,
                ADMIN_USAGE_PAGE_SIZE,
                filter,
                { signal },
            )

        const normalized = normalizePageResponse(response)

        return {
            rows: normalized.content,
            hasNext:
                page + 1 < normalized.totalPages,
            hasPrevious: page > 0,
        }
    }

    const dateFilter = {
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
    }

    if (tab === 'users') {
        const response = scopedOrganizationId
            ? await getOrganizationUsageUsers(
                scopedOrganizationId,
                page,
                ADMIN_USAGE_PAGE_SIZE,
                dateFilter,
                { signal },
            )
            : await getUsageByUsers(
                page,
                ADMIN_USAGE_PAGE_SIZE,
                dateFilter,
                { signal },
            )

        const normalized = normalizePageResponse(response)

        return {
            rows: normalized.content,
            hasNext:
                page + 1 < normalized.totalPages,
            hasPrevious: page > 0,
        }
    }

    if (tab === 'models') {
        const rows = scopedOrganizationId
            ? await getOrganizationUsageModels(
                scopedOrganizationId,
                dateFilter,
                { signal },
            )
            : await getUsageByModels(
                dateFilter,
                { signal },
            )

        return {
            rows,
            hasNext: false,
            hasPrevious: false,
        }
    }

    const rows = scopedOrganizationId
        ? await getOrganizationUsageDaily(
            scopedOrganizationId,
            dateFilter,
            { signal },
        )
        : await getUsageDaily(
            dateFilter,
            { signal },
        )

    return {
        rows,
        hasNext: false,
        hasPrevious: false,
    }
}
