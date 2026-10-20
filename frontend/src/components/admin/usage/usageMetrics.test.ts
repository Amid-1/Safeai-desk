import { describe, expect, it } from 'vitest'
import type { UsageAmounts } from '../../../api/usageApi'
import { sumDecimalAmounts, summarizeUsageRows } from './usageMetrics'

function row(partial: Partial<UsageAmounts> = {}): UsageAmounts {
    return {
        inputTokens: '9007199254740993', outputTokens: '5', totalTokens: '9007199254740998',
        partialInputTokens: '2', partialOutputTokens: '3', partialTotalTokens: '5',
        costUsd: '0.000000000001', currency: 'USD',
        coverage: {
            assistantMessages: '1', completedResponses: '1', refusedResponses: '0',
            incompleteResponses: '0', failedMessages: '0', unclassifiedResponses: '0',
            availableUsageMessages: '1', partialUsageMessages: '0', missingUsageMessages: '0',
            usageNotApplicableMessages: '0', pricedMessages: '0', freeMessages: '1',
            unpricedMessages: '0', pricingFailedMessages: '0', pricingNotApplicableMessages: '0',
            usageComplete: true, pricingComplete: true,
        }, ...partial,
    }
}

describe('exact usage metrics', () => {
    it('sums decimals without binary rounding or losing tiny prices', () => {
        expect(sumDecimalAmounts(['0.000000000001', '0.000000000002'])).toBe('0.000000000003')
        expect(sumDecimalAmounts(['1.10', '2.999'])).toBe('4.099')
        expect(sumDecimalAmounts([])).toBe('0')
        expect(() => sumDecimalAmounts(['1e3'])).toThrow()
    })
    it('keeps confirmed and partial counts separate, detects unknown cost', () => {
        const sum = summarizeUsageRows([row(), row({costUsd: null, coverage: {...row().coverage, pricingComplete: false}})])
        expect(sum.total).toBe('18014398509481996')
        expect(sum.partial).toBe('10')
        expect(sum.knownCost).toBe('0.000000000001')
        expect(sum.unknownCostRows).toBe(1)
        expect(sum.completeRows).toBe(1)
    })
    it('does not silently sum different currencies', () => {
        expect(summarizeUsageRows([row(), row({currency: 'EUR'})]).knownCost).toBeNull()
    })
})
