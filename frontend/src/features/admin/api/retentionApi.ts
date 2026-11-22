// frontend/src/features/admin/api/retentionApi.ts
import { apiRequest } from '../../../shared/api/http'
import { expectRecord } from '../../../shared/api/runtime'

export type RetentionGate = { configured: boolean; workerInitialized: boolean }
export type RetentionStatus = {
    chatAllowed: boolean
    knowledgeAllowed: boolean
    version: number
    chat: RetentionGate
    document: RetentionGate
    base: RetentionGate
    userRetentionSeconds: number
}
export type RetentionChange = Pick<RetentionStatus, 'chatAllowed' | 'knowledgeAllowed'> & {
    expectedVersion: number
}

function boolean(value: unknown): boolean {
    if (typeof value !== 'boolean') throw new Error('Некорректный ответ политики хранения.')
    return value
}

function count(value: unknown): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
        throw new Error('Некорректный ответ политики хранения.')
    }
    return value
}

function gate(value: unknown): RetentionGate {
    const data = expectRecord(value, 'retention.gate')
    return {configured: boolean(data.configured), workerInitialized: boolean(data.workerInitialized)}
}

function parse(value: unknown): RetentionStatus {
    const data = expectRecord(value, 'retention')
    return {
        chatAllowed: boolean(data.chatAllowed),
        knowledgeAllowed: boolean(data.knowledgeAllowed),
        version: count(data.version),
        chat: gate(data.chat), document: gate(data.document), base: gate(data.base),
        userRetentionSeconds: count(data.userRetentionSeconds),
    }
}

export async function loadRetention(signal?: AbortSignal): Promise<RetentionStatus> {
    return parse(await apiRequest<unknown>('/api/admin/retention', {signal, method: 'GET'}))
}

export async function saveRetention(change: RetentionChange): Promise<RetentionStatus> {
    return parse(await apiRequest<unknown>('/api/admin/retention', {method: 'PUT', json: change}))
}
