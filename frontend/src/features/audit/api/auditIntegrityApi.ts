// frontend/src/features/audit/api/auditIntegrityApi.ts
import { API_TIMEOUTS, apiRequest } from '../../../shared/api/http'
import { contractError, expectRecord } from '../../../shared/api/runtime'

export const INTEGRITY_STATES = ['NOT_CONFIGURED', 'VERIFIED', 'VIOLATION', 'UNAVAILABLE', 'STALE', 'INVALID', 'PENDING'] as const
export type IntegrityState = typeof INTEGRITY_STATES[number]
export type IntegrityStatus = {
    state: IntegrityState
    checkedEvents: number | null
    violations: number | null
    unanchoredEvents: number | null
    checkedAt: string | null
    freshUntil: string | null
    archiveStatus: 'AVAILABLE' | 'UNKNOWN'
}

export function parseIntegrityStatus(input: unknown): IntegrityStatus {
    const value = expectRecord(input, 'auditIntegrity')
    const state = INTEGRITY_STATES.find(item => item === value.state)
    if (!state || (value.archiveStatus !== 'AVAILABLE' && value.archiveStatus !== 'UNKNOWN')) {
        throw contractError('Неизвестное состояние целостности')
    }
    function count(key: string): number | null {
        const item = value[key]
        if (item == null) return null
        if (typeof item !== 'number' || !Number.isSafeInteger(item) || item < 0 || item > 10_000_000) {
            throw contractError('Недопустимый счётчик целостности')
        }
        return item
    }
    function date(key: string): string | null {
        const item = value[key]
        if (item == null) return null
        if (typeof item !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(item)
            || !Number.isFinite(Date.parse(item))) throw contractError('Недопустимое время проверки')
        return item
    }
    const checkedEvents = count('checkedEvents'), violations = count('violations'), unanchoredEvents = count('unanchoredEvents')
    const checkedAt = date('checkedAt'), freshUntil = date('freshUntil')
    const available = state === 'VERIFIED' || state === 'VIOLATION' || state === 'PENDING'
    if (available) {
        if (checkedEvents === null || violations === null || unanchoredEvents === null || !checkedAt || !freshUntil
            || Date.parse(freshUntil) - Date.parse(checkedAt) !== 900_000 || value.archiveStatus !== 'AVAILABLE'
            || violations > checkedEvents + unanchoredEvents
            || state !== (violations > 0 ? 'VIOLATION' : unanchoredEvents > 0 ? 'PENDING' : 'VERIFIED')) {
            throw contractError('Несогласованный отчёт целостности')
        }
    } else if (checkedEvents !== null || violations !== null || unanchoredEvents !== null || freshUntil !== null
        || value.archiveStatus !== 'UNKNOWN') {
        throw contractError('Недостоверные показатели целостности')
    }
    return { state, checkedEvents, violations, unanchoredEvents, checkedAt, freshUntil, archiveStatus: value.archiveStatus }
}

export async function getAuditIntegrity(signal: AbortSignal): Promise<IntegrityStatus> {
    return parseIntegrityStatus(await apiRequest<unknown>('/api/admin/audit-events/integrity', {
        method: 'GET', signal, timeoutMs: API_TIMEOUTS.report,
    }))
}
