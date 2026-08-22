// frontend/src/pages/adminUsage.helpers.ts

import type {
    UsageFilter,
} from '../api/adminApi'
import {
    uuidPathSegment,
} from '../api/query'
import {
    assertDateRange,
    createRecentDateRange,
    toUtcExclusiveEndOfDayIso,
    toUtcStartOfDayIso,
} from '../utils/date'

export type UsageTab =
    | 'summary'
    | 'users'
    | 'models'
    | 'daily'

export type UsageDraftFilter = {
    dateFrom: string
    dateTo: string
    model: string
    organizationId: string
}

export type UsageUrlState = {
    tab: UsageTab
    page: number
    draft: UsageDraftFilter
}

export const MAX_USAGE_RANGE_DAYS =
    366

const USAGE_TABS:
readonly UsageTab[] = [
    'summary',
    'users',
    'models',
    'daily',
]

export function createDefaultUsageDraftFilter(
    now: Date = new Date(),
): UsageDraftFilter {
    const range =
        createRecentDateRange(
            30,
            'UTC',
            now,
        )

    return {
        dateFrom: range.dateFrom,
        dateTo: range.dateTo,
        model: '',
        organizationId: '',
    }
}

export function readUsageUrlState(
    search: string,
    superAdmin: boolean,
    now: Date = new Date(),
): UsageUrlState {
    const params =
        new URLSearchParams(search)

    const fallback =
        createDefaultUsageDraftFilter(
            now,
        )

    const tabValue =
        params.get('report')

    const tab =
        isUsageTab(tabValue)
            ? tabValue
            : 'summary'

    const dateFrom =
        params.get('dateFrom') ?? ''
    const dateTo =
        params.get('dateTo') ?? ''

    let safeDateFrom =
        fallback.dateFrom
    let safeDateTo =
        fallback.dateTo

    try {
        if (dateFrom && dateTo) {
            assertDateRange(
                dateFrom,
                dateTo,
                MAX_USAGE_RANGE_DAYS,
            )

            safeDateFrom = dateFrom
            safeDateTo = dateTo
        }
    } catch {
        // Invalid URL filter falls back to
        // an explicit bounded UTC range.
    }

    return {
        tab,
        page:
            normalizePageParameter(
                params.get('page'),
            ),
        draft: {
            dateFrom: safeDateFrom,
            dateTo: safeDateTo,
            model:
                normalizeModel(
                    params.get('model')
                    ?? '',
                ),
            organizationId:
                superAdmin
                    ? normalizeOptionalUuid(
                        params.get(
                            'organizationId',
                        ) ?? '',
                    )
                    : '',
        },
    }
}

export function toUsageFilter(
    draft: UsageDraftFilter,
    superAdmin: boolean,
): {
    draft: UsageDraftFilter
    filter: UsageFilter
    organizationId: string
} {
    const normalized:
    UsageDraftFilter = {
        dateFrom:
            draft.dateFrom.trim(),
        dateTo:
            draft.dateTo.trim(),
        model:
            normalizeModel(
                draft.model,
            ),
        organizationId:
            superAdmin
                ? normalizeOptionalUuid(
                    draft.organizationId,
                )
                : '',
    }

    if (
        !normalized.dateFrom
        || !normalized.dateTo
    ) {
        throw new Error(
            'Для usage-отчёта необходимо задать начало и окончание периода.',
        )
    }

    assertDateRange(
        normalized.dateFrom,
        normalized.dateTo,
        MAX_USAGE_RANGE_DAYS,
    )

    return {
        draft: normalized,
        filter: {
            dateFrom:
                toUtcStartOfDayIso(
                    normalized.dateFrom,
                ),
            dateTo:
                toUtcExclusiveEndOfDayIso(
                    normalized.dateTo,
                ),
            model:
                normalized.model
                || undefined,
        },
        organizationId:
            normalized.organizationId,
    }
}

function normalizeModel(
    value: string,
): string {
    const normalized = value.trim()

    if (
        normalized.length > 100
    ) {
        throw new Error(
            'Название модели не должно превышать 100 символов.',
        )
    }

    if (containsControlCharacter(normalized)) {
        throw new Error(
            'Название модели содержит управляющие символы.',
        )
    }

    return normalized
}

function containsControlCharacter(value: string): boolean {
    return Array.from(value).some((character) => {
        const codePoint = character.codePointAt(0) ?? 0
        return codePoint < 32 || codePoint === 127
    })
}

function normalizeOptionalUuid(
    value: string,
): string {
    const normalized = value.trim()

    return normalized
        ? uuidPathSegment(normalized)
        : ''
}

function normalizePageParameter(
    value: string | null,
): number {
    if (
        value === null
        || !/^\d+$/.test(value)
    ) {
        return 0
    }

    const page = Number(value)

    return Number.isSafeInteger(page)
        ? page
        : 0
}

function isUsageTab(
    value: string | null,
): value is UsageTab {
    return value !== null
        && USAGE_TABS.includes(
            value as UsageTab,
        )
}
