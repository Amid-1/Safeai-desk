// frontend/src/features/audit/api/auditIntegrityApi.test.ts
import { describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { getAuditIntegrity, parseIntegrityStatus } from './auditIntegrityApi'

vi.mock('../../../shared/api/http', () => ({ apiRequest: vi.fn(), API_TIMEOUTS: { report: 15000 } }))

describe('Контракт независимого аудита', () => {
    it('использует отдельный защищённый endpoint и передаёт отмену', async () => {
        const controller = new AbortController()
        vi.mocked(apiRequest).mockResolvedValue({ state: 'UNAVAILABLE', archiveStatus: 'UNKNOWN' })
        expect(await getAuditIntegrity(controller.signal)).toEqual({ state: 'UNAVAILABLE', archiveStatus: 'UNKNOWN',
            checkedEvents: null, violations: null, unanchoredEvents: null, checkedAt: null, freshUntil: null })
        expect(apiRequest).toHaveBeenCalledWith('/api/admin/audit-events/integrity', {
            method: 'GET', signal: controller.signal, timeoutMs: 15000,
        })
    })

    it('принимает предупреждение о незакреплённых событиях, не подменяя его успехом', () => {
        const value = { state: 'PENDING', archiveStatus: 'AVAILABLE', checkedEvents: 5, violations: 0, unanchoredEvents: 1,
            checkedAt: '2026-11-21T08:00:00Z', freshUntil: '2026-11-21T08:15:00Z' }
        expect(parseIntegrityStatus(value).state).toBe('PENDING')
        expect(() => parseIntegrityStatus({ ...value, freshUntil: '2026-11-21T08:16:00Z' })).toThrow()
        expect(() => parseIntegrityStatus({ ...value, checkedEvents: 10_000_001 })).toThrow()
        expect(() => parseIntegrityStatus({ ...value, checkedEvents: 0.5 })).toThrow()
        expect(() => parseIntegrityStatus({ ...value, checkedEvents: null })).toThrow()
        expect(() => parseIntegrityStatus({ ...value, checkedAt: null })).toThrow()
    })
})
