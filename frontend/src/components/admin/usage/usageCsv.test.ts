import { describe, expect, it } from 'vitest'
import type { UsageSummary } from '../../../api/usageApi'
import { serializeUsageCsv } from './usageCsv'

const SOURCE: UsageSummary = {
    userId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', userEmail: '=HYPERLINK("https://attacker")',
    model: '\t+cmd', inputTokens: '9007199254740993', outputTokens: '3', totalTokens: '9007199254740996',
    partialInputTokens: '0', partialOutputTokens: '0', partialTotalTokens: '0',
    costUsd: '0.00000001', currency: 'USD',
    coverage: {
        assistantMessages: '1', completedResponses: '1', refusedResponses: '0',
        incompleteResponses: '0', failedMessages: '0', unclassifiedResponses: '0',
        availableUsageMessages: '1', partialUsageMessages: '0', missingUsageMessages: '0',
        usageNotApplicableMessages: '0', pricedMessages: '1', freeMessages: '0',
        unpricedMessages: '0', pricingFailedMessages: '0', pricingNotApplicableMessages: '0',
        usageComplete: true, pricingComplete: true,
    },
}

describe('usage CSV export', () => {
    it('neutralizes formulas without changing source data', () => {
        const csv = serializeUsageCsv('summary', [SOURCE])
        expect(csv).toContain('"\'=HYPERLINK(""https://attacker"")"')
        expect(csv).toContain('"\' +cmd"')
        expect(csv).toContain('"9007199254740993"')
        expect(csv).toContain('"0.00000001"')
        expect(SOURCE.userEmail).toBe('=HYPERLINK("https://attacker")')
    })
    it('does not create synthetic zero for unknown cost', () => {
        expect(serializeUsageCsv('summary', [{...SOURCE, costUsd: null}])).toContain('"";"USD"')
    })
})
