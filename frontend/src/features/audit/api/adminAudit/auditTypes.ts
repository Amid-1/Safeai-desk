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

    actorUserId?: string
    actorEmail?: string

    dateFrom?: string
    dateTo?: string

    targetOrganizationId?: string
}

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
