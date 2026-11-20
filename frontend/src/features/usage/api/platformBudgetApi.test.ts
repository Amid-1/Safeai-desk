// frontend/src/features/usage/api/platformBudgetApi.test.ts
import { describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { getPlatformBudgetSummary, parsePlatformBudgetSummary } from './platformBudgetApi'

vi.mock('../../../shared/api/http', () => ({apiRequest: vi.fn(), API_TIMEOUTS: {report: 1000}}))
const valid = {
    periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-20T00:00:00Z',
    total: 7, enabled: 6, within: 1, nearLimit: 1, exceeded: 1, noLimit: 1, unknown: 1, disabled: 1, unconfigured: 1,
}
describe('platform budget contract', () => {
    it('requests a read-only platform summary', async () => {
        vi.mocked(apiRequest).mockResolvedValue(valid)
        const signal = new AbortController().signal
        expect(await getPlatformBudgetSummary(signal)).toEqual(valid)
        expect(apiRequest).toHaveBeenCalledWith('/api/admin/models/policies/budget-summary', {method: 'GET', signal, timeoutMs: 1000})
    })
    it.each([{unknown: -1}, {within: '1'}, {total: 8}, {enabled: 8}, {periodStart: 'bad'}, {asOf: valid.periodEnd}])('rejects invalid summary %j', change => {
        expect(() => parsePlatformBudgetSummary({...valid, ...change})).toThrow()
    })
})
