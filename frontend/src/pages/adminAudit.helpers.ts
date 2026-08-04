import type {
    AuditEventFilter,
} from '../api/adminApi'
import {
    uuidPathSegment,
} from '../api/query'
import {
    addCalendarDays,
    assertDateRange,
    createRecentDateRange,
    getLocalDateValue,
    parseDateValue,
    toLocalExclusiveEndOfDayIso,
    toLocalStartOfDayIso,
} from '../utils/date'
import type {
    AuditDraftFilter,
    DatePreset,
} from '../components/admin/audit/types'
import {
    createDefaultAuditDraftFilter,
} from '../components/admin/audit/types'

export const MAX_AUDIT_RANGE_DAYS =
    366

export type AuditUrlState = {
    draftFilter: AuditDraftFilter
    page: number
}

export function readAuditUrlState(
    search: string,
    superAdmin: boolean,
    now: Date = new Date(),
): AuditUrlState {
    const fallback =
        createDefaultAuditDraftFilter(
            now,
        )

    const params =
        new URLSearchParams(search)

    const eventType =
        safelyNormalize(
            () =>
                normalizeEventType(
                    params.get('eventType')
                    ?? '',
                ),
            '',
        )

    const actorUserId =
        safelyNormalize(
            () =>
                normalizeOptionalUuid(
                    params.get(
                        'actorUserId',
                    ) ?? '',
                ),
            '',
        )

    const actorEmail =
        safelyNormalize(
            () =>
                normalizeActorEmail(
                    params.get(
                        'actorEmail',
                    ) ?? '',
                ),
            '',
        )

    const targetOrganizationId =
        superAdmin
            ? safelyNormalize(
                () =>
                    normalizeOptionalUuid(
                        params.get(
                            'targetOrganizationId',
                        ) ?? '',
                    ),
                '',
            )
            : ''

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
                MAX_AUDIT_RANGE_DAYS,
            )

            safeDateFrom = dateFrom
            safeDateTo = dateTo
        }
    } catch {
        // URL не должен ломать страницу.
        // Используется bounded default range.
    }

    return {
        draftFilter: {
            eventType,
            actorUserId,
            actorEmail,
            dateFrom: safeDateFrom,
            dateTo: safeDateTo,
            targetOrganizationId,
        },
        page:
            normalizePageParameter(
                params.get('page'),
            ),
    }
}

export function toAuditEventFilter(
    draft: AuditDraftFilter,
    superAdmin: boolean,
): {
    draft: AuditDraftFilter
    filter: AuditEventFilter
} {
    const normalizedDraft:
        AuditDraftFilter = {
        eventType:
            normalizeEventType(
                draft.eventType,
            ),
        actorUserId:
            normalizeOptionalUuid(
                draft.actorUserId,
            ),
        actorEmail:
            normalizeActorEmail(
                draft.actorEmail,
            ),
        dateFrom:
            draft.dateFrom.trim(),
        dateTo:
            draft.dateTo.trim(),
        targetOrganizationId:
            superAdmin
                ? normalizeOptionalUuid(
                    draft.targetOrganizationId,
                )
                : '',
    }

    if (
        !normalizedDraft.dateFrom
        || !normalizedDraft.dateTo
    ) {
        throw new Error(
            'Для аудита необходимо задать начало и окончание периода.',
        )
    }

    assertDateRange(
        normalizedDraft.dateFrom,
        normalizedDraft.dateTo,
        MAX_AUDIT_RANGE_DAYS,
    )

    return {
        draft: normalizedDraft,
        filter: {
            eventType:
                normalizedDraft.eventType
                || undefined,

            actorUserId:
                normalizedDraft.actorUserId
                || undefined,

            actorEmail:
                normalizedDraft.actorEmail
                || undefined,

            dateFrom:
                toLocalStartOfDayIso(
                    normalizedDraft.dateFrom,
                ),

            dateTo:
                toLocalExclusiveEndOfDayIso(
                    normalizedDraft.dateTo,
                ),

            targetOrganizationId:
                superAdmin
                    ? (
                        normalizedDraft
                            .targetOrganizationId
                        || undefined
                    )
                    : undefined,
        },
    }
}

export function buildAuditSearch(
    draft: AuditDraftFilter,
    page: number,
    superAdmin: boolean,
): string {
    const params =
        new URLSearchParams()

    setIfPresent(
        params,
        'eventType',
        draft.eventType,
    )
    setIfPresent(
        params,
        'actorUserId',
        draft.actorUserId,
    )
    setIfPresent(
        params,
        'actorEmail',
        draft.actorEmail,
    )
    setIfPresent(
        params,
        'dateFrom',
        draft.dateFrom,
    )
    setIfPresent(
        params,
        'dateTo',
        draft.dateTo,
    )

    if (superAdmin) {
        setIfPresent(
            params,
            'targetOrganizationId',
            draft.targetOrganizationId,
        )
    }

    if (page > 0) {
        params.set(
            'page',
            String(page),
        )
    }

    const query = params.toString()

    return query ? `?${query}` : ''
}

export function applyAuditDatePreset(
    draft: AuditDraftFilter,
    preset: DatePreset,
    now: Date = new Date(),
): AuditDraftFilter {
    const today =
        getLocalDateValue(now)

    const days = (() => {
        switch (preset) {
            case 'today':
                return 1

            case 'yesterday':
                return 1

            case 'last7Days':
                return 7

            case 'last30Days':
                return 30

            case 'last365Days':
                return 365
        }
    })()

    const dateTo =
        preset === 'yesterday'
            ? addCalendarDays(
                today,
                -1,
            )
            : today

    const range =
        createRecentDateRange(
            days,
            'LOCAL',
            new Date(
                ...localDateConstructorArgs(
                    dateTo,
                ),
            ),
        )

    return {
        ...draft,
        dateFrom: range.dateFrom,
        dateTo: range.dateTo,
    }
}

export function createResetAuditFilter(
    now: Date = new Date(),
): AuditDraftFilter {
    return createDefaultAuditDraftFilter(
        now,
    )
}

function normalizeOptionalUuid(
    value: string,
): string {
    const normalized = value.trim()

    if (!normalized) {
        return ''
    }

    return uuidPathSegment(
        normalized,
    )
}

function normalizeActorEmail(
    value: string,
): string {
    const normalized = value
        .trim()
        .toLowerCase()

    if (
        normalized.length > 320
    ) {
        throw new Error(
            'Actor email или префикс не должен превышать 320 символов.',
        )
    }

    if (
        /[\u0000-\u001f\u007f]/.test(
            normalized,
        )
    ) {
        throw new Error(
            'Actor email содержит управляющие символы.',
        )
    }

    return normalized
}

function normalizeEventType(
    value: string,
): string {
    const normalized = value.trim()

    if (!normalized) {
        return ''
    }

    if (
        normalized.length > 128
        || !/^[A-Z][A-Z0-9_]*$/.test(
            normalized,
        )
    ) {
        throw new Error(
            'Тип события имеет некорректный формат.',
        )
    }

    return normalized
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

function setIfPresent(
    params: URLSearchParams,
    key: string,
    value: string,
) {
    if (value) {
        params.set(key, value)
    }
}

function localDateConstructorArgs(
    dateValue: string,
): [
    number,
    number,
    number,
] {
    const {
        year,
        month,
        day,
    } = parseDateValue(
        dateValue,
    )

    return [
        year,
        month - 1,
        day,
    ]
}


function safelyNormalize<T>(
    operation: () => T,
    fallback: T,
): T {
    try {
        return operation()
    } catch {
        return fallback
    }
}
