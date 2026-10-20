import { describe, expect, it } from 'vitest'
import { buildUsageBreakdownChartData } from './usageBreakdownChartData'

const base = {
    inputTokens: '167',
    outputTokens: '102',
    totalTokens: '269',
    partialInputTokens: '0',
    partialOutputTokens: '0',
    partialTotalTokens: '0',
    userEmail: 'admin@test.com',
    model: 'mock-safeai',
} satisfies Parameters<typeof buildUsageBreakdownChartData>[1][number]

describe('model/user/summary chart data — scoped to loaded rows', () => {
    it('renders all three aggregation labels without changing the source', () => {
        const rows = [base]
        expect(buildUsageBreakdownChartData('summary', rows).data[0].label)
            .toBe('admin@test.com · mock-safeai')
        expect(buildUsageBreakdownChartData('users', rows).data[0].label)
            .toBe('admin@test.com')
        expect(buildUsageBreakdownChartData('models', rows).data[0].label)
            .toBe('mock-safeai')
        expect(rows[0]).toEqual(base)
    })

    it('ranks confirmed totals exactly and caps large pages at 12', () => {
        const rows = Array.from({ length: 20 }, (_, index) => ({
            ...base,
            userEmail: `user${index}@example.com`,
            inputTokens: String(index + 1),
            outputTokens: '0',
            totalTokens: String(index + 1),
        }))
        const result = buildUsageBreakdownChartData('users', rows)
        expect(result.totalRows).toBe(20)
        expect(result.shownRows).toBe(12)
        expect(result.data[0].label).toBe('user19@example.com')
        expect(rows[0].userEmail).toBe('user0@example.com')
    })

    it('does not render imprecise bars for unsafe integers', () => {
        const result = buildUsageBreakdownChartData('summary', [{
            ...base,
            inputTokens: '9007199254740993',
            totalTokens: '9007199254740993',
        }])
        expect(result.available).toBe(false)
        expect(result.data).toEqual([])
    })

    it('uses confirmed tokens, without mixing partial usage', () => {
        const result = buildUsageBreakdownChartData('summary', [{
            ...base,
            // Partial usage is evidence, NOT confirmed usage. Even values
            // exceeding Number.MAX_SAFE_INTEGER must not enter this chart.
            partialInputTokens: '9007199254740993',
            partialOutputTokens: '999999',
            partialTotalTokens: '9007199255740992',
        }])
        expect(result.available).toBe(true)
        expect(result.data[0].input).toBe(167)
        expect(result.data[0].output).toBe(102)
    })

    it('keeps stable order for equal confirmed totals and does not mutate inputs', () => {
        const rows = [
            { ...base, userEmail: 'first@example.com' },
            { ...base, userEmail: 'second@example.com' },
        ]
        const before = structuredClone(rows)
        const result = buildUsageBreakdownChartData('users', rows)
        expect(result.data.map(item => item.label)).toEqual([
            'first@example.com',
            'second@example.com',
        ])
        expect(rows).toEqual(before)
    })

    it('rejects malformed confirmed counters rather than drawing misleading bars', () => {
        for (const invalid of ['-1', '1.5', 'not-a-number']) {
            const result = buildUsageBreakdownChartData('models', [{
                ...base,
                inputTokens: invalid,
            }])
            expect(result.available).toBe(false)
            expect(result.shownRows).toBe(0)
            expect(result.data).toEqual([])
        }
    })

    it('keeps the exact maximum safe confirmed value renderable', () => {
        const result = buildUsageBreakdownChartData('users', [{
            ...base,
            inputTokens: '9007199254740991',
            outputTokens: '0',
            totalTokens: '9007199254740991',
        }])
        expect(result.available).toBe(true)
        expect(result.data[0].input).toBe(Number.MAX_SAFE_INTEGER)
    })

})
