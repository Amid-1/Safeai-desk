// ============================================================
// frontend/src/features/audit/api/adminAudit/auditTypes.ts
// ============================================================



export type JsonPrimitive =
    | string
    | number
    | boolean
    | null

export type JsonValue =
    | JsonPrimitive
    | JsonValue[]
    | {
        [key: string]: JsonValue
    }

export type AuditEvent = {
    id: string

    targetOrganizationId: string
    targetOrganizationName: string | null

    actorUserId: string | null
    actorOrganizationId: string | null
    actorEmail: string | null
    actorDisplayName: string | null

    eventType: string

    /**
     * Дополнительная защита на уровне отображения.
     * Сервер обязан применять список разрешённых полей для каждого типа события.
     */
    details: Record<string, JsonValue>
    detailsTruncated: boolean
    detailsInvalid: boolean

    createdAt: string
}

export type AuditEventFilter = {
    eventType?: string
    category?: AuditCategory
    metric?: AuditMetric

    actorUserId?: string
    actorEmail?: string

    dateFrom?: string
    dateTo?: string

    targetOrganizationId?: string
}

export const AUDIT_CATEGORIES = ['SECURITY', 'USERS', 'ORGANIZATIONS', 'AI', 'KNOWLEDGE', 'SYSTEM'] as const
export const AUDIT_METRICS = ['SUCCESS', 'DENIED', 'CRITICAL'] as const
export type AuditCategory = typeof AUDIT_CATEGORIES[number]
export type AuditMetric = typeof AUDIT_METRICS[number]
export type AuditSummary = { total: number; successful: number; denied: number; critical: number }

export type AuditActorDirectoryItem = {
    actorUserId: string | null
    actorOrganizationId: string | null
    actorEmail: string | null
    actorDisplayName: string | null
}

export type AuditTargetOrganizationDirectoryItem = {
    targetOrganizationId: string
    targetOrganizationName: string | null
}

export type RequestOptions = {
    signal?: AbortSignal
}
