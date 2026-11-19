// frontend/src/features/usage/api/usage/usageBudget.test.ts
import { describe, expect, it, vi } from 'vitest'
import { getOrganizationBudgetStatus, parseBudgetStatus } from './usageBudget'
import { apiRequest } from '../../../../shared/api/http'

vi.mock('../../../../shared/api/http', () => ({ apiRequest: vi.fn(), API_TIMEOUTS: { report: 1000 } }))
const organizationId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const base = {
    organizationId, policyVersion: 2, policyEnabled: true, enforcement: 'HARD',
    periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-19T12:00:00Z',
    monthlyBudgetUsd: '10.000000000001', knownActualUsd: '0.000000000001',
    reservedUsd: '2', knownCommittedUsd: '2.000000000001', unknownCommittedCostCount: 0,
    remainingUsd: '8', status: 'KNOWN',
}
describe('budget snapshot contract', () => {
    it('preserves exact USD strings and fetches only the scoped endpoint', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce(base)
        const signal = new AbortController().signal
        const result = await getOrganizationBudgetStatus(organizationId, signal)
        expect(result.knownActualUsd).toBe('0.000000000001')
        expect(result.remainingUsd).toBe('8')
        expect(apiRequest).toHaveBeenCalledWith(`/api/admin/models/policies/${organizationId}/budget-status`,
            { method: 'GET', signal, timeoutMs: 1000 })
    })
    it('does not substitute zero for unknown or unset budget', () => {
        expect(parseBudgetStatus({...base, status: 'UNKNOWN', unknownCommittedCostCount: 1, remainingUsd: null}).remainingUsd).toBeNull()
        expect(parseBudgetStatus({...base, status: 'NO_LIMIT', monthlyBudgetUsd: null, remainingUsd: null}).monthlyBudgetUsd).toBeNull()
    })
    it('rejects malformed amounts, statuses, dates and falsely known remaining balances', () => {
        for (const invalid of [
            {remainingUsd: '0', unknownCommittedCostCount: 1}, {remainingUsd: null}, {remainingUsd: 8},
            {status: 'INVALID'}, {enforcement: 'OTHER'}, {asOf: '2026-12-01T00:00:00Z'},
            {periodStart: 'not-a-date'}, {policyEnabled: false}, {knownActualUsd: '-1'},
            {status: 'UNKNOWN', remainingUsd: '0'}, {remainingUsd: undefined},
        ]) expect(() => parseBudgetStatus({...base, ...invalid})).toThrow()
    })
})
