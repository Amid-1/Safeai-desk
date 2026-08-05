import {
    createRecentDateRange,
} from '../../../utils/date'

export type DatePreset =
    | 'today'
    | 'yesterday'
    | 'last7Days'
    | 'last30Days'
    | 'last365Days'

export type AuditDraftFilter = {
    eventType: string

    actorUserId: string
    actorEmail: string

    dateFrom: string
    dateTo: string

    targetOrganizationId: string
}

export function createDefaultAuditDraftFilter(
    now: Date = new Date(),
): AuditDraftFilter {
    const range =
        createRecentDateRange(
            30,
            'LOCAL',
            now,
        )

    return {
        eventType: '',

        actorUserId: '',
        actorEmail: '',

        dateFrom: range.dateFrom,
        dateTo: range.dateTo,

        targetOrganizationId: '',
    }
}

export function auditDraftFiltersEqual(
    first: AuditDraftFilter,
    second: AuditDraftFilter,
): boolean {
    return first.eventType
        === second.eventType
        && first.actorUserId
            === second.actorUserId
        && first.actorEmail
            === second.actorEmail
        && first.dateFrom
            === second.dateFrom
        && first.dateTo
            === second.dateTo
        && first.targetOrganizationId
            === second.targetOrganizationId
}