// frontend/src/features/models/controller/useAdminModelsDiagnostics.test.tsx
// Runtime probe: permission, concurrency, context identity and failure semantics.
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { probeRuntimeModel } from '../../../entities/model/api/modelApi'
import type { RuntimeModelProbe } from '../../../entities/model/api/modelApi'
import { createRuntimeModelStatus } from '../../../test/fixtures/modelControlPlaneTestFixtures'
import { useAdminModelsDiagnostics } from './useAdminModelsDiagnostics'

vi.mock('../../../entities/model/api/modelApi', () => ({
    probeRuntimeModel: vi.fn(),
    getModelRouteDecision: vi.fn(),
}))

const runtime = createRuntimeModelStatus()
const probe: RuntimeModelProbe = {provider: runtime.provider, model: runtime.model,
    status: 'AVAILABLE', checkedAt: '2026-11-19T12:00:00Z', latencyMs: 30,
    httpStatus: 200, message: 'Success'}
const api = vi.mocked(probeRuntimeModel)
beforeEach(() => vi.resetAllMocks())

describe('useAdminModelsDiagnostics', () => {
    it('sends no probe without role or enabled connection', async () => {
        const error = vi.fn()
        const {result, rerender} = renderHook(({allowed, enabled}) =>
            useAdminModelsDiagnostics(allowed, error, {...runtime, enabled}),
        {initialProps: {allowed: false, enabled: true}})
        await act(async () => result.current.handleRuntimeProbe())
        rerender({allowed: true, enabled: false})
        await act(async () => result.current.handleRuntimeProbe())
        expect(api).not.toHaveBeenCalled()
    })
    it('rejects double actions and invalidates pending responses on A → B → A', async () => {
        let complete!: (value: RuntimeModelProbe) => void
        api.mockReturnValueOnce(new Promise(resolve => {complete = resolve}))
        const error = vi.fn()
        const {result, rerender} = renderHook(({model}) =>
            useAdminModelsDiagnostics(true, error, {...runtime, model}),
        {initialProps: {model: runtime.model}})
        act(() => {void result.current.handleRuntimeProbe(); void result.current.handleRuntimeProbe()})
        expect(api).toHaveBeenCalledTimes(1)
        expect(result.current.runtimeProbePending).toBe(true)
        expect(result.current.runtimeHealthUnconfirmed).toBe(true)
        rerender({model: 'new-model'})
        rerender({model: runtime.model})
        expect(api.mock.calls[0]?.[0]?.signal?.aborted).toBe(true)
        await act(async () => complete(probe))
        expect(result.current.runtimeProbe).toBeNull()
        expect(result.current.runtimeProbePending).toBe(false)
        expect(result.current.runtimeHealthUnconfirmed).toBe(false)
    })
    it('shows only matching successful results and clears them for a failed recheck', async () => {
        api.mockResolvedValueOnce({...probe, model: 'different'})
        api.mockResolvedValueOnce(probe)
        api.mockRejectedValueOnce(new Error('secret'))
        const error = vi.fn()
        const {result, rerender} = renderHook(({allowed}) =>
            useAdminModelsDiagnostics(allowed, error, runtime), {initialProps: {allowed: true}})
        await act(async () => result.current.handleRuntimeProbe())
        expect(result.current.runtimeProbe).toBeNull()
        expect(error).toHaveBeenLastCalledWith(expect.stringContaining('Подключение изменилось'))
        await act(async () => result.current.handleRuntimeProbe())
        expect(result.current.runtimeProbe).toEqual(probe)
        expect(result.current.runtimeHealthUnconfirmed).toBe(false)
        await act(async () => result.current.handleRuntimeProbe())
        expect(result.current.runtimeProbe).toBeNull()
        expect(result.current.runtimeHealthUnconfirmed).toBe(true)
        expect(error).toHaveBeenLastCalledWith('Не удалось проверить доступность подключённой модели.')
        api.mockResolvedValueOnce(probe)
        await act(async () => result.current.handleRuntimeProbe())
        rerender({allowed: false})
        expect(result.current.runtimeProbe).toBeNull()
    })
})
