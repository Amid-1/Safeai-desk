// frontend/src/features/usage/api/usageProviderApi.test.ts
import { describe, expect, it, vi } from 'vitest'
import { getUsageProviders, parseProviderRows } from './usageProviderApi'
import { parseExportJob, readUsageExport, startUsageExport } from './usageExportApi'
import { apiRequest } from '../../../shared/api/http'

vi.mock('../../../shared/api/http', () => ({apiRequest: vi.fn(), API_TIMEOUTS: {report: 10_000}}))

const row = {provider: 'mock', attempts: '2', unresolved: '1', unknownCost: '1', unknownUsage: '1',
    input: '9007199254740993', output: '0', knownCostUsd: '0.000000000000000001'}

describe('Точные данные провайдеров', () => {
    it('сохраняет строковые числа без округления', () => {
        expect(parseProviderRows([row])).toEqual([row])
    })
    it('отказывается от несогласованных, повторных и числовых данных', () => {
        expect(() => parseProviderRows([{...row, unknownCost: '0'}])).toThrow()
        expect(() => parseProviderRows([row, row])).toThrow()
        expect(() => parseProviderRows([{...row, input: 1}])).toThrow()
    })
    it('проверяет состояние задания перед скачиванием', () => {
        const job = {id: 'ac000000-0000-4000-8000-000000000001', state: 'READY',
            expiresAt: '2026-11-21T10:00:00Z', failure: null}
        expect(parseExportJob(job)).toEqual(job)
        expect(() => parseExportJob({...job, state: 'FAILED'})).toThrow()
        expect(() => parseExportJob({...job, id: '../download'})).toThrow()
    })
    it('проверяет область серверного отчёта и возвращает только согласованные данные', async () => {
        const expected = {from: '2026-11-01T00:00:00Z', to: '2026-11-22T00:00:00Z',
            organizationId: null, model: null, userId: null,
            aggregationBasis: 'PHYSICAL_ATTEMPT_STARTED_AT', rows: [row]}
        vi.mocked(apiRequest).mockResolvedValueOnce(expected)
        const filter = {kind: 'providers', dateFrom: '2026-11-01', dateTo: '2026-11-21', organizationId: null}
        const signal = new AbortController().signal
        expect(await getUsageProviders(filter, signal)).toEqual([row])
        vi.mocked(apiRequest).mockResolvedValueOnce({...expected, from: '2026-11-02T00:00:00Z'})
        await expect(getUsageProviders(filter, signal)).rejects.toThrow()
    })
    it('отправляет UTC-границы и проверяет идентификатор задания при опросе', async () => {
        const job = {id: 'ac000000-0000-4000-8000-000000000001', expiresAt: '2026-11-21T10:00:00Z',
            state: 'READY', failure: null}
        vi.mocked(apiRequest).mockResolvedValueOnce(job)
        const signal = new AbortController().signal
        const filter = {kind: 'summary', dateFrom: '2026-11-01', dateTo: '2026-11-21', organizationId: null}
        expect(await startUsageExport(filter, signal)).toEqual(job)
        expect(apiRequest).toHaveBeenLastCalledWith('/api/admin/usage/exports', expect.objectContaining({
            method: 'POST', json: expect.objectContaining({kind: 'SUMMARY', dateTo: '2026-11-22T00:00:00.000Z'}),
        }))
        vi.mocked(apiRequest).mockResolvedValueOnce({...job, id: 'ac000000-0000-4000-8000-000000000002'})
        await expect(readUsageExport(job.id, signal)).rejects.toThrow()
    })
})
