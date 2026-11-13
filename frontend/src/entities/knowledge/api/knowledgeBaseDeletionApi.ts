// frontend/src/entities/knowledge/api/knowledgeBaseDeletionApi.ts
import {API_TIMEOUTS, apiRequest} from '../../../shared/api/http'
import {uuidPathSegment} from '../../../shared/api/query'
import {expectEnum, expectInstant, expectRecord, expectUuid} from '../../../shared/api/runtime'

const STATES = ['DELETING', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION', 'DELETED'] as const
export type BaseDeletionState = typeof STATES[number]
export type BaseDeletionOperation = {
    operationId: string
    knowledgeBaseId: string
    state: BaseDeletionState
    createdAt: string
    completedAt: string | null
    errorCode: string | null
}
const path = (baseId: string) => `/api/knowledge-bases/${uuidPathSegment(baseId)}`
function parseOperation(value: unknown): BaseDeletionOperation {
    const record = expectRecord(value, 'baseDeletion')
    return {
        operationId: expectUuid(record.operationId, 'baseDeletion.operationId'),
        knowledgeBaseId: expectUuid(record.knowledgeBaseId, 'baseDeletion.knowledgeBaseId'),
        state: expectEnum(record.state, 'baseDeletion.state', STATES),
        createdAt: expectInstant(record.createdAt, 'baseDeletion.createdAt'),
        completedAt: record.completedAt == null ? null
            : expectInstant(record.completedAt, 'baseDeletion.completedAt'),
        errorCode: record.errorCode == null ? null
            : typeof record.errorCode === 'string' ? record.errorCode
                : (() => { throw new Error('Invalid baseDeletion.errorCode') })(),
    }
}
export async function baseDeletionCapability(baseId: string, signal?: AbortSignal): Promise<boolean> {
    const result = expectRecord(await apiRequest<unknown>(`${path(baseId)}/base-deletions/capability`,
        {method: 'GET', signal, timeoutMs: API_TIMEOUTS.default}), 'baseDeletionCapability')
    if (typeof result.allowed !== 'boolean') throw new Error('Invalid base deletion capability')
    return result.allowed
}
export async function acceptBaseDeletion(baseId: string, version: number,
                                         key: string): Promise<BaseDeletionOperation> {
    return parseOperation(await apiRequest<unknown>(path(baseId), {
        method: 'DELETE',
        headers: {'If-Match': `"${version}"`, 'Idempotency-Key': key},
        timeoutMs: API_TIMEOUTS.default,
    }))
}
export async function getBaseDeletion(baseId: string,
                                      signal?: AbortSignal): Promise<BaseDeletionOperation> {
    return parseOperation(await apiRequest<unknown>(`${path(baseId)}/base-deletions/current`,
        {method: 'GET', signal, timeoutMs: API_TIMEOUTS.default}))
}
export async function listBaseDeletions(signal?: AbortSignal): Promise<BaseDeletionOperation[]> {
    const result = await apiRequest<unknown>('/api/knowledge-bases/base-deletions',
        {method: 'GET', signal, timeoutMs: API_TIMEOUTS.default})
    if (!Array.isArray(result)) throw new Error('Invalid base deletion operations')
    return result.map(parseOperation)
}
