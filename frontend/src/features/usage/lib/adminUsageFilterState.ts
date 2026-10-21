// frontend/src/features/usage/lib/adminUsageFilterState.ts

import type {
    UsageSummaryFilter,
} from '../api/usageApi'
import {
    uuidPathSegment,
} from '../../../shared/api/query'
import {
    assertDateRange,
    createRecentDateRange,
    toUtcExclusiveEndOfDayIso,
    toUtcStartOfDayIso,
} from '../../../shared/lib/date'

export type UsageReportTab =
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
    tab: UsageReportTab
    page: number
    draft: UsageDraftFilter
}

export type NormalizedUsageFilter = {
    draft: UsageDraftFilter
    filter: UsageSummaryFilter
    organizationId: string
}

export type UsageUrlValues = {
    tab: UsageReportTab
    page: number
    dateFrom: string
    dateTo: string
    model: string
    organizationId: string
}

export const MAX_USAGE_RANGE_DAYS = 366

const USAGE_TABS: readonly UsageReportTab[] = [
    'summary',
    'users',
    'models',
    'daily',
]

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function createDefaultUsageDraftFilter(
    now: Date = new Date(),
): UsageDraftFilter {
    const range = createRecentDateRange(
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

export function defaultUtcRange(
    now: Date = new Date(),
): {
    dateFrom: string
    dateTo: string
} {
    const draft = createDefaultUsageDraftFilter(now)

    return {
        dateFrom: draft.dateFrom,
        dateTo: draft.dateTo,
    }
}

/**
 * URL is untrusted input. Invalid filters never crash the page: every invalid
 * value is reduced to a bounded safe default and is validated again on Apply.
 */
export function readUsageUrlState(
    search: string,
    superAdmin: boolean,
    now: Date = new Date(),
): UsageUrlState {
    const params = new URLSearchParams(search)
    const fallback = createDefaultUsageDraftFilter(now)

    const requestedDateFrom = params.get('dateFrom') ?? ''
    const requestedDateTo = params.get('dateTo') ?? ''

    let dateFrom = fallback.dateFrom
    let dateTo = fallback.dateTo

    if (requestedDateFrom && requestedDateTo) {
        try {
            assertDateRange(
                requestedDateFrom,
                requestedDateTo,
                MAX_USAGE_RANGE_DAYS,
            )

            dateFrom = requestedDateFrom
            dateTo = requestedDateTo
        } catch {
            // Keep the explicit bounded fallback.
        }
    }

    return {
        tab: parseTab(params.get('report')),
        page: parsePage(params.get('page')),
        draft: {
            dateFrom,
            dateTo,
            model: safeNormalizeModel(
                params.get('model') ?? '',
            ),
            organizationId: superAdmin
                ? safeNormalizeOptionalUuid(
                    params.get('organizationId') ?? '',
                )
                : '',
        },
    }
}

export function toUsageFilter(
    draft: UsageDraftFilter,
    superAdmin: boolean,
): NormalizedUsageFilter {
    const normalized: UsageDraftFilter = {
        dateFrom: draft.dateFrom.trim(),
        dateTo: draft.dateTo.trim(),
        model: normalizeModel(draft.model),
        organizationId: superAdmin
            ? normalizeOptionalUuid(
                draft.organizationId,
            )
            : '',
    }

    if (!normalized.dateFrom || !normalized.dateTo) {
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
            dateFrom: toUtcStartOfDayIso(
                normalized.dateFrom,
            ),
            dateTo: toUtcExclusiveEndOfDayIso(
                normalized.dateTo,
            ),
            model: normalized.model || undefined,
        },
        organizationId: normalized.organizationId,
    }
}

export function validateFilters(
    dateFrom: string,
    dateTo: string,
    organizationId: string,
): string | null {
    try {
        toUsageFilter(
            {
                dateFrom,
                dateTo,
                model: '',
                organizationId,
            },
            true,
        )
        return null
    } catch (error) {
        return error instanceof Error
            ? normalizeValidationMessage(error.message)
            : 'Некорректные параметры отчёта.'
    }
}

export function buildUsageSearchParams(
    values: UsageUrlValues,
): URLSearchParams {
    const next = new URLSearchParams()

    next.set('report', values.tab)
    next.set('dateFrom', values.dateFrom)
    next.set('dateTo', values.dateTo)

    if (values.page > 0) {
        next.set('page', String(values.page))
    }

    // Preserve the summary-only model filter while the user inspects another
    // tab so switching views never silently loses a deliberate selection.
    if (values.model) {
        next.set('model', values.model)
    }

    if (values.organizationId) {
        next.set(
            'organizationId',
            values.organizationId,
        )
    }

    return next
}

export function parseTab(
    value: string | null,
): UsageReportTab {
    return value !== null
        && USAGE_TABS.includes(
            value as UsageReportTab,
        )
        ? value as UsageReportTab
        : 'summary'
}

export function parsePage(
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

export function isAbortError(
    error: unknown,
): boolean {
    return error instanceof Error
        && (
            error.name === 'AbortError'
            || (
                'errorCode' in error
                && (error as { errorCode?: string }).errorCode
                === 'REQUEST_ABORTED'
            )
        )
}

export function isUsageOrganizationId(
    value: string,
): boolean {
    return UUID_PATTERN.test(value.trim())
}

function normalizeModel(
    value: string,
): string {
    const normalized = value.trim()

    if (normalized.length > 100) {
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

function normalizeOptionalUuid(
    value: string,
): string {
    const normalized = value.trim()

    return normalized
        ? uuidPathSegment(normalized)
        : ''
}

function safeNormalizeModel(
    value: string,
): string {
    try {
        return normalizeModel(value)
    } catch {
        return ''
    }
}

function safeNormalizeOptionalUuid(
    value: string,
): string {
    try {
        return normalizeOptionalUuid(value)
    } catch {
        return ''
    }
}

function containsControlCharacter(
    value: string,
): boolean {
    return Array.from(value).some((character) => {
        const codePoint = character.codePointAt(0) ?? 0
        return codePoint < 32 || codePoint === 127
    })
}

function normalizeValidationMessage(
    message: string,
): string {
    if (message.includes('Некорректная')) {
        return 'Некорректная календарная дата.'
    }

    return message
}
