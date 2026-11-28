// frontend/src/features/usage/controller/useUsageExport.test.tsx
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { downloadUsageExport, readUsageExport, startUsageExport, type ExportJob } from '../api/usageExportApi'
import { useUsageExport } from './useUsageExport'
import { ApiError } from '../../../shared/api/http'

vi.mock('../api/usageExportApi', () => ({
    startUsageExport: vi.fn(), readUsageExport: vi.fn(), downloadUsageExport: vi.fn(),
}))
const job: ExportJob = {id: 'ac000000-0000-4000-8000-000000000001',
    state: 'READY', expiresAt: '2026-11-21T11:00:00Z', failure: null}
const filter = {kind: 'summary', dateFrom: '2026-11-01', dateTo: '2026-11-21', organizationId: null}

describe('Асинхронный экспорт', () => {
    beforeEach(() => vi.resetAllMocks())
    it('отдельно создаёт отчёт и скачивает его только по явной команде', async () => {
        vi.mocked(startUsageExport).mockResolvedValue(job)
        vi.mocked(downloadUsageExport).mockResolvedValue(undefined)
        const {result} = renderHook(() => useUsageExport('scope'))
        await act(async () => { await result.current.run(filter) })
        expect(result.current.ready).toBe(true)
        expect(downloadUsageExport).not.toHaveBeenCalled()
        await act(async () => { await result.current.download() })
        expect(downloadUsageExport).toHaveBeenCalledWith(job.id, expect.any(AbortSignal))
    })
    it('не переносит готовый файл или незавершённый запрос между областями', async () => {
        vi.mocked(startUsageExport).mockImplementation(() => new Promise(() => {}))
        const {result, rerender} = renderHook(({scope}) => useUsageExport(scope),
            {initialProps: {scope: 'first'}})
        act(() => { void result.current.run(filter) })
        const signal = vi.mocked(startUsageExport).mock.calls[0][1]
        expect(result.current.pending).toBe(true)
        rerender({scope: 'second'})
        expect(signal.aborted).toBe(true)
        expect(result.current.pending).toBe(false)
        expect(result.current.ready).toBe(false)
    })
    it('сообщает о превышении лимита и не предлагает усечённый CSV', async () => {
        vi.mocked(startUsageExport).mockResolvedValue({...job, state: 'FAILED', failure: 'LIMIT_EXCEEDED'})
        const {result} = renderHook(() => useUsageExport('scope'))
        await act(async () => { await result.current.run(filter) })
        expect(result.current.error).toContain('10 000')
        expect(result.current.ready).toBe(false)
    })
    it('ожидает серверное задание, не начиная новый экспорт при опросе', async () => {
        vi.mocked(startUsageExport).mockResolvedValue({...job, state: 'QUEUED'})
        vi.mocked(readUsageExport).mockResolvedValue(job)
        const {result} = renderHook(() => useUsageExport('scope'))
        act(() => { void result.current.run(filter) })
        await waitFor(() => expect(result.current.ready).toBe(true), {timeout: 4000})
        expect(startUsageExport).toHaveBeenCalledTimes(1)
        expect(readUsageExport).toHaveBeenCalledWith(job.id, expect.any(AbortSignal))
    })
    it('восстанавливает опрос того же задания после временного отказа', async () => {
        vi.useFakeTimers()
        try {
            vi.mocked(startUsageExport).mockResolvedValue({...job, state: 'QUEUED'})
            vi.mocked(readUsageExport).mockRejectedValueOnce(new ApiError('offline', {}, 503))
                .mockResolvedValueOnce(job)
            const {result} = renderHook(() => useUsageExport('scope'))
            act(() => { void result.current.run(filter) })
            await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
            expect(result.current.pending).toBe(true)
            expect(result.current.error).toContain('повторно')
            await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
            expect(result.current.ready).toBe(true)
            expect(startUsageExport).toHaveBeenCalledTimes(1)
            expect(readUsageExport).toHaveBeenCalledTimes(2)
        } finally {
            vi.useRealTimers()
        }
    })
})
