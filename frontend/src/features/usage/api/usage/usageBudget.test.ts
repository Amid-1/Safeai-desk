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
    it('requires explicit nullable amounts in a real NO_LIMIT response', () => {
        const wire = {
            organizationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', policyVersion: 2,
            policyEnabled: true, enforcement: 'SOFT', periodStart: '2026-11-01T00:00:00Z',
            periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-19T18:47:57.109125Z',
            knownActualUsd: '0.000000000000', reservedUsd: '0', knownCommittedUsd: '0.000000000000',
            unknownCommittedCostCount: 0, status: 'NO_LIMIT',
        }
        expect(() => parseBudgetStatus(wire)).toThrow()
        expect(() => parseBudgetStatus({...wire, monthlyBudgetUsd: null})).toThrow()
        expect(() => parseBudgetStatus({...wire, remainingUsd: null})).toThrow()
        expect(parseBudgetStatus({...wire, monthlyBudgetUsd: null, remainingUsd: null})).toMatchObject({
            status: 'NO_LIMIT', monthlyBudgetUsd: null, remainingUsd: null,
            knownActualUsd: '0.000000000000',
        })
    })
    it('accepts equivalent UTC zone spelling, preserving nanoseconds and explicit nulls', () => {
        const result = parseBudgetStatus({...base, policyEnabled: false, policyVersion: 0,
            status: 'UNCONFIGURED', monthlyBudgetUsd: null, remainingUsd: null,
            periodStart: '2026-11-01T00:00:00+00:00', asOf: '2026-11-19T12:00:00.123456789+00:00'})
        expect(result.periodStart).toBe('2026-11-01T00:00:00Z')
        expect(result.asOf).toBe('2026-11-19T12:00:00.123456789Z')
        expect(result.monthlyBudgetUsd).toBeNull()
        expect(result.remainingUsd).toBeNull()
    })
    it('rejects malformed amounts, statuses, dates and falsely known remaining balances', () => {
        for (const invalid of [
            {remainingUsd: '0', unknownCommittedCostCount: 1}, {remainingUsd: null}, {remainingUsd: 8},
            {status: 'INVALID'}, {enforcement: 'OTHER'}, {asOf: '2026-12-01T00:00:00Z'},
            {periodStart: 'not-a-date'}, {policyEnabled: false}, {knownActualUsd: '-1'},
            {status: 'UNKNOWN', remainingUsd: '0'}, {remainingUsd: undefined},
            {asOf: '2026-11-31T00:00:00Z'}, {asOf: '2026-11-19T12:00:00+03:00'},
            {monthlyBudgetUsd: undefined}, {knownActualUsd: 0},
        ]) expect(() => parseBudgetStatus({...base, ...invalid})).toThrow()
    })
})
