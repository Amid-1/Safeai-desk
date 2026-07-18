// ============================================================
// frontend/src/components/admin/audit/types.ts
// ============================================================
export type DatePreset =
    | 'today'
    | 'yesterday'
    | 'last7Days'
    | 'last30Days'
    | 'all'

export type AuditDraftFilter = {
    eventType: string
    userId: string
    dateFrom: string
    dateTo: string
    organizationId: string
}

export const EMPTY_AUDIT_DRAFT_FILTER: AuditDraftFilter = {
    eventType: '',
    userId: '',
    dateFrom: '',
    dateTo: '',
    organizationId: '',
}
