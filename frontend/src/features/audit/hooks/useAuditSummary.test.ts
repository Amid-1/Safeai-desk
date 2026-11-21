// frontend/src/features/audit/hooks/useAuditSummary.test.ts
import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { getAuditSummary, type AuditSummary } from '../api/adminAuditApi'
import { useAuditSummary } from './useAuditSummary'

vi.mock('../api/adminAuditApi', () => ({ getAuditSummary: vi.fn() }))

describe('useAuditSummary', () => {
    it('не показывает ответ прежней области доступа после переключения', async () => {
        let resolveOld!: (value: AuditSummary) => void
        let resolveNew!: (value: AuditSummary) => void
        const mock = vi.mocked(getAuditSummary)
        mock.mockReset()
        mock.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
            .mockImplementationOnce(() => new Promise(resolve => { resolveNew = resolve }))
        const { result, rerender } = renderHook(({ scope }) => useAuditSummary(scope, {}, 0), {
            initialProps: { scope: 'first' },
        })
        const oldSignal = mock.mock.calls[0][1]?.signal
        rerender({ scope: 'second' })
        expect(oldSignal?.aborted).toBe(true)
        expect(result.current.summary).toBeNull()
        await act(async () => resolveOld({ total: 999, successful: 999, denied: 0, critical: 0 }))
        expect(result.current.summary).toBeNull()
        await act(async () => resolveNew({ total: 1, successful: 0, denied: 1, critical: 0 }))
        await waitFor(() => expect(result.current.summary?.total).toBe(1))
        expect(result.current.summaryLoading).toBe(false)
    })
})
