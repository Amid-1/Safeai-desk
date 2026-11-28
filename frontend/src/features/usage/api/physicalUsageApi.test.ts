// frontend/src/features/usage/api/physicalUsageApi.test.ts
import { describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { getPhysicalUsageReport, parsePhysicalUsageReport } from './physicalUsageApi'

vi.mock('../../../shared/api/http', async importOriginal => ({
    ...await importOriginal<typeof import('../../../shared/api/http')>(), apiRequest: vi.fn(),
}))

const ORG = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const SAMPLE = {
    from: '2026-11-01T00:00:00Z', to: '2026-11-02T00:00:00Z', organizationId: ORG,
    aggregationBasis: 'PHYSICAL_ATTEMPT_STARTED_AT', planSelectionBasis: 'PLANS_WITH_PHYSICAL_START_IN_RANGE',
    currency: 'USD', physicalAttempts: '2', startedAttempts: '0', ambiguousAttempts: '1',
    knownExecutedAttempts: '1', knownNotExecutedAttempts: '0', knownRejectedAttempts: '0',
    costUnverifiableAttempts: '1', usageUnverifiableAttempts: '1', knownInputTokens: '9007199254740993',
    knownOutputTokens: '0', knownCostUsd: '0.000000000000000001', costComplete: false, usageComplete: false,
    plans: '1', costMatchedPlans: '0', costMismatchPlans: '0', unverifiablePlans: '1', fullyReconciled: false,
}

describe('Точный физический отчёт', () => {
    it('не округляет большие счётчики и дробную стоимость', () => {
        const report = parsePhysicalUsageReport(SAMPLE)
        expect(report.knownInputTokens).toBe('9007199254740993')
        expect(report.knownCostUsd).toBe('0.000000000000000001')
    })
    it.each([
        {knownInputTokens: 1}, {knownInputTokens: '9223372036854775808'}, {knownCostUsd: '1e-9'},
        {knownCostUsd: '-1'}, {costComplete: true}, {physicalAttempts: '3'}, {costMatchedPlans: '1'},
        {usageUnverifiableAttempts: '0'}, {fullyReconciled: true}, {organizationId: undefined},
    ])('отклоняет некорректный контракт %j', invalid => {
        expect(() => parsePhysicalUsageReport({...SAMPLE, ...invalid})).toThrow()
    })
    it('проверяет серверную область, полуоткрытый период и передаёт сигнал отмены', async () => {
        const signal = new AbortController().signal
        const scope = {
            dateFrom: '2026-11-01', dateTo: '2026-11-01', organizationId: null, expectedOrganizationId: ORG,
        }
        const request = vi.mocked(apiRequest)
        request.mockResolvedValueOnce(SAMPLE)
        await expect(getPhysicalUsageReport(scope, signal)).resolves.toMatchObject({organizationId: ORG})
        const [path, options] = request.mock.calls.at(-1)!
        const url = new URL(path, 'https://example.test')
        expect(url.pathname).toBe('/api/admin/usage/physical-attempts/report')
        expect(url.searchParams.get('dateFrom')).toBe('2026-11-01T00:00:00.000Z')
        expect(url.searchParams.get('dateTo')).toBe('2026-11-02T00:00:00.000Z')
        expect(url.searchParams.has('organizationId')).toBe(false)
        expect(options?.signal).toBe(signal)
        request.mockResolvedValueOnce({...SAMPLE, organizationId: null})
        await expect(getPhysicalUsageReport(scope, signal)).rejects.toThrow()
        request.mockResolvedValueOnce({...SAMPLE, to: '2026-11-03T00:00:00Z'})
        await expect(getPhysicalUsageReport(scope, signal)).rejects.toThrow()
    })
})
