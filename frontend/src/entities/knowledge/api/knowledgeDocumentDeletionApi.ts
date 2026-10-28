// frontend/src/entities/knowledge/api/knowledgeDocumentDeletionApi.ts
import {API_TIMEOUTS, apiRequest} from '../../../shared/api/http'
import {uuidPathSegment} from '../../../shared/api/query'
import {expectInstant, expectRecord, expectUuid, expectEnum} from '../../../shared/api/runtime'

const STATES = ['DELETING', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION', 'DELETED'] as const
export type KnowledgeDeletionState = typeof STATES[number]
export type KnowledgeDocumentDeletion = {
    operationId: string
    documentId: string
    state: KnowledgeDeletionState
    createdAt: string
    completedAt: string | null
}

const basePath = (baseId: string) => `/api/knowledge-bases/${uuidPathSegment(baseId)}`

function parseDeletion(raw: unknown): KnowledgeDocumentDeletion {
    const data = expectRecord(raw, 'documentDeletion')
    return {
        operationId: expectUuid(data.operationId, 'documentDeletion.operationId'),
        documentId: expectUuid(data.documentId, 'documentDeletion.documentId'),
        state: expectEnum(data.state, 'documentDeletion.state', STATES),
        createdAt: expectInstant(data.createdAt, 'documentDeletion.createdAt'),
        completedAt: data.completedAt === null ? null
            : expectInstant(data.completedAt, 'documentDeletion.completedAt'),
    }
}

export async function deletionCapability(baseId: string, signal?: AbortSignal): Promise<boolean> {
    const value = await apiRequest<unknown>(`${basePath(baseId)}/document-deletions/capability`,
        {method: 'GET', signal, timeoutMs: API_TIMEOUTS.default})
    const response = expectRecord(value, 'documentDeletionCapability')
    if (typeof response.allowed !== 'boolean') {
        throw new Error('Invalid document erasure capability response')
    }
    return response.allowed
}

export async function listDocumentDeletions(baseId: string, signal?: AbortSignal): Promise<KnowledgeDocumentDeletion[]> {
    const value = await apiRequest<unknown>(`${basePath(baseId)}/document-deletions`,
        {method: 'GET', signal, timeoutMs: API_TIMEOUTS.default})
    if (!Array.isArray(value)) throw new Error('Invalid document deletion list')
    return value.map(parseDeletion)
}

export async function acceptDocumentDeletion(baseId: string, documentId: string,
    expectedVersion: number, idempotencyKey: string): Promise<KnowledgeDocumentDeletion> {
    const value = await apiRequest<unknown>(`${basePath(baseId)}/documents/${uuidPathSegment(documentId)}`,
        {method: 'DELETE', headers: {
            'If-Match': `"${expectedVersion}"`,
            'Idempotency-Key': idempotencyKey,
        }, timeoutMs: API_TIMEOUTS.default})
    return parseDeletion(value)
}
