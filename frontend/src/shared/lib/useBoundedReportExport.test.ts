// frontend/src/shared/lib/useBoundedReportExport.test.ts
import { act, renderHook } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { useBoundedReportExport } from './useBoundedReportExport'

it('rejects overflow and empty results, and reports safe failures', async () => {
    const { result } = renderHook(() => useBoundedReportExport('tenant'))
    const download = vi.fn()
    await act(() => result.current.run(async () => null, download))
    expect(result.current.error).toContain('Больше 100')
    await act(() => result.current.run(async () => [], download))
    expect(result.current.error).toContain('Нет записей')
    await act(() => result.current.run(async () => { throw new Error('secret') }, download))
    expect(result.current.error).not.toContain('secret')
    expect(download).not.toHaveBeenCalled()
    await act(() => result.current.run(async () => ['result'], download))
    expect(download).toHaveBeenCalledWith(['result'])
    expect(result.current.error).toBe('')
})

it('aborts an obsolete tenant export, even if the transport ignores abort', async () => {
    const { result, rerender } = renderHook(({ scope }) => useBoundedReportExport(scope), { initialProps: { scope: 'one' } })
    let resolve!: (rows: string[]) => void
    let signal!: AbortSignal
    const download = vi.fn()
    let running!: Promise<void>
    act(() => {
        running = result.current.run<string>(s => { signal = s; return new Promise(r => { resolve = r }) }, download)
    })
    expect(result.current.pending).toBe(true)
    rerender({ scope: 'two' })
    expect(signal.aborted).toBe(true)
    expect(result.current.pending).toBe(false)
    await act(async () => { resolve(['old tenant']); await running })
    expect(download).not.toHaveBeenCalled()
})
