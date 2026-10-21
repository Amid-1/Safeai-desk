// ============================================================
// frontend/src/api/knowledge/knowledgeTypes.ts
// ============================================================



export const VISIBILITIES = [
    'ORGANIZATION',
    'MEMBERS',
] as const

export const ACCESS_LEVELS = [
    'VIEWER',
    'EDITOR',
    'OWNER',
] as const

export type KnowledgeBaseVisibility =
    typeof VISIBILITIES[number]

export type KnowledgeBaseAccessLevel =
    typeof ACCESS_LEVELS[number]

export type KnowledgeBaseAccess = {
    knowledgeBaseId: string
    accessLevel: KnowledgeBaseAccessLevel
    administrator: boolean
    canEditDocuments: boolean
    canManageBase: boolean
    canManageMembers: boolean
}

export type KnowledgeBase = {
    id: string
    organizationId: string

    name: string
    description: string | null

    visibility:
        KnowledgeBaseVisibility

    enabled: boolean

    createdByUserId: string

    version: number

    createdAt: string
    updatedAt: string
}

export type KnowledgeBaseMember = {
    knowledgeBaseId: string
    userId: string

    email: string
    fullName: string | null

    accessLevel:
        KnowledgeBaseAccessLevel

    version: number

    createdAt: string
    updatedAt: string
}

export type KnowledgeMemberCandidate = {
    userId: string
    email: string
    fullName: string | null
}

export type CreateKnowledgeBaseRequest = {
    name: string
    description: string | null

    visibility:
        KnowledgeBaseVisibility
}

export type UpdateKnowledgeBaseRequest = {
    name: string
    description: string | null

    visibility:
        KnowledgeBaseVisibility

    enabled: boolean
    expectedVersion: number
}

export type CreateKnowledgeBaseMemberRequest = {
    userId: string

    accessLevel:
        KnowledgeBaseAccessLevel
}

export type UpdateKnowledgeBaseMemberRequest = {
    accessLevel:
        KnowledgeBaseAccessLevel

    expectedVersion: number
}

export type RequestOptions = {
    signal?: AbortSignal
}

/** Фильтрация выполняется сервером до пагинации, а не внутри страницы браузера. */
export type KnowledgeBaseListFilters = {
    query?: string
    enabled?: boolean
    visibility?: KnowledgeBaseVisibility
}
