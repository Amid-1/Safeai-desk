// frontend/src/features/erasure/api/erasureApi.ts
import { apiRequest } from '../../../shared/api/http'
import { expectRecord } from '../../../shared/api/runtime'

export type ResourceType = 'USER_ACCOUNT' | 'CHAT' | 'KNOWLEDGE_DOCUMENT'
    | 'KNOWLEDGE_BASE' | 'ORGANIZATION'
export type Operation = 'DELETE_ACCOUNT' | 'REDACT_CONTENT'
    | 'DELETE_OBJECT' | 'OFFBOARD_TENANT'
export type Target = {
    resourceType: ResourceType
    resourceId: string
    operation: Operation
    scope: string
}
export type Preview = {
    previewId: string
    previewDigest: string
    expectedResourceVersion: number
    expiresAt: string
    executionAvailable: false
}
export type CaseView = Target & {
    requestId: string
    state: 'DRAFT' | 'SUBMITTED' | 'WITHDRAWN'
    version: number
    createdAt: string
    executionStarted: false
    completionProven: false
}

function boolFalse(value: unknown): false {
    if (value !== false) throw new Error('Сервер вернул неподтверждённое состояние удаления.')
    return false
}
function count(value: unknown): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
        throw new Error('Некорректная версия заявки.')
    }
    return value
}
function text(value: unknown): string {
    if (typeof value !== 'string' || !value) throw new Error('Некорректный ответ сервера.')
    return value
}
function parseCase(value: unknown): CaseView {
    const d = expectRecord(value, 'erasure.case')
    const state = text(d.state)
    if (!['DRAFT', 'SUBMITTED', 'WITHDRAWN'].includes(state)) {
        throw new Error('Неизвестное состояние заявки.')
    }
    return {
        requestId: text(d.requestId),
        resourceType: text(d.resourceType) as ResourceType,
        resourceId: text(d.resourceId),
        operation: text(d.operation) as Operation,
        scope: text(d.scope),
        state: state as CaseView['state'],
        version: count(d.version),
        createdAt: text(d.createdAt),
        executionStarted: boolFalse(d.executionStarted),
        completionProven: boolFalse(d.completionProven),
    }
}
function parsePage(value: unknown): CaseView[] {
    const d = expectRecord(value, 'erasure.page')
    if (!Array.isArray(d.items) || d.items.length > 100) {
        throw new Error('Некорректный список заявок.')
    }
    return d.items.map(parseCase)
}
function uuid(): string {
    return crypto.randomUUID()
}
export async function loadMine(signal?: AbortSignal): Promise<CaseView[]> {
    return parsePage(await apiRequest('/api/erasure/requests', { method: 'GET', signal }))
}
export async function loadTenantQueue(signal?: AbortSignal): Promise<CaseView[]> {
    return parsePage(await apiRequest('/api/admin/erasure/queue', { method: 'GET', signal }))
}
export async function previewCase(target: Target): Promise<Preview> {
    const d = expectRecord(await apiRequest('/api/erasure/previews', {
        method: 'POST', json: target,
    }), 'erasure.preview')
    return {
        previewId: text(d.previewId),
        previewDigest: text(d.previewDigest),
        expectedResourceVersion: count(d.expectedResourceVersion),
        expiresAt: text(d.expiresAt),
        executionAvailable: boolFalse(d.executionAvailable),
    }
}
export async function createDraft(target: Target, preview: Preview,
    reasonCode: string): Promise<CaseView> {
    return parseCase(await apiRequest('/api/erasure/requests', {
        method: 'POST', headers: { 'Idempotency-Key': uuid() },
        json: { target, previewId: preview.previewId,
            previewDigest: preview.previewDigest,
            expectedResourceVersion: preview.expectedResourceVersion, reasonCode },
    }))
}
export async function submitDraft(item: CaseView): Promise<CaseView> {
    return parseCase(await apiRequest(`/api/erasure/requests/${item.requestId}/submit`, {
        method: 'POST', headers: { 'If-Match': `"${item.version}"`, 'Idempotency-Key': uuid() },
    }))
}
export async function withdrawCase(item: CaseView): Promise<CaseView> {
    return parseCase(await apiRequest(`/api/erasure/requests/${item.requestId}/withdraw`, {
        method: 'POST', headers: { 'If-Match': `"${item.version}"`, 'Idempotency-Key': uuid() },
    }))
}
