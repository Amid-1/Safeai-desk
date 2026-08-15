import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    parseUsageDailySummary,
    parseUsagePageResponse,
    parseUsageSummary,
} from './usageApi'

const USER_ID =
    '11111111-1111-4111-8111-111111111111'

function nestedSummary() {
    return {
        userId: USER_ID,
        userEmail: 'user@test.com',
        model: 'mock-safeai',
        responses: {
            assistantMessages: 10,
            completedResponses: 8,
            refusedResponses: 1,
            incompleteResponses: 0,
            failedMessages: 1,
            unclassifiedMessages: 0,
        },
        usage: {
            confirmedInputTokens: 100,
            confirmedOutputTokens: 50,
            confirmedTotalTokens: 150,
            partialKnownInputTokens: 7,
            partialKnownOutputTokens: 3,
            partialKnownTotalTokens: 10,
            availableUsageMessages: 8,
            partialUsageMessages: 1,
            missingUsageMessages: 1,
            usageNotApplicableMessages: 0,
            usageComplete: false,
        },
        cost: {
            knownCostUsd: '1.500000000000',
            currency: 'USD',
            pricedMessages: 6,
            freeMessages: 2,
            unpricedMessages: 1,
            pricingFailedMessages: 1,
            pricingNotApplicableMessages: 0,
            pricingComplete: false,
        },
    }
}

describe('usageApi current production contract', () => {
    it('parses nested UsageResponseSummary/UsageTokenSummary/UsageCostSummary', () => {
        const result = parseUsageSummary(
            nestedSummary(),
        )

        expect(result.inputTokens).toBe('100')
        expect(result.outputTokens).toBe('50')
        expect(result.totalTokens).toBe('150')
        expect(result.partialTotalTokens).toBe('10')
        expect(result.costUsd).toBe(
            '1.500000000000',
        )
        expect(result.coverage.usageComplete)
            .toBe(false)
        expect(result.coverage.pricingComplete)
            .toBe(false)
        expect(result.coverage.unpricedMessages)
            .toBe('1')
    })

    it('accepts currentUserEmail from the backend DTO', () => {
        const source = nestedSummary()
        delete (source as Partial<typeof source>).userEmail

        const result = parseUsageSummary({
            ...source,
            currentUserEmail: 'current@test.com',
        })

        expect(result.userEmail).toBe('current@test.com')
    })

    it('adapts the usage Slice contract without requiring totals', () => {
        const result = parseUsagePageResponse(
            {
                content: [],
                page: 0,
                size: 50,
                first: true,
                last: true,
                hasNext: false,
                hasPrevious: false,
            },
            parseUsageSummary,
        )

        expect(result).toEqual({
            content: [],
            page: 0,
            size: 50,
            totalElements: 0,
            totalPages: 0,
        })
    })

    it('keeps large token counters as decimal strings without JS Number aggregation', () => {
        const source = nestedSummary()
        source.usage.confirmedInputTokens =
            '9007199254740993' as unknown as number
        source.usage.confirmedOutputTokens =
            '7' as unknown as number
        source.usage.confirmedTotalTokens =
            '9007199254741000' as unknown as number

        const result = parseUsageSummary(source)

        expect(result.totalTokens).toBe(
            '9007199254741000',
        )
    })

    it('does not present known zero as complete pricing when unpriced messages exist', () => {
        const source = nestedSummary()
        source.cost.knownCostUsd = '0'
        source.cost.pricingComplete = false
        source.cost.unpricedMessages = 2

        const result = parseUsageSummary(source)

        expect(result.costUsd).toBe('0')
        expect(result.coverage.pricingComplete)
            .toBe(false)
        expect(result.coverage.unpricedMessages)
            .toBe('2')
    })

    it('supports previous flat DTO during rolling deployment', () => {
        const result = parseUsageSummary({
            userId: USER_ID,
            userEmail: 'user@test.com',
            model: 'mock-safeai',
            inputTokens: 10,
            outputTokens: 5,
            totalTokens: 15,
            costUsd: 0,
        })

        expect(result.totalTokens).toBe('15')
        expect(result.costUsd).toBe('0')
    })

    it('fails fast when backend total differs from components', () => {
        const source = nestedSummary()
        source.usage.confirmedTotalTokens = 151

        expect(() =>
            parseUsageSummary(source),
        ).toThrow('не равен сумме')
    })

    it('makes UTC daily aggregation contract explicit', () => {
        const source = nestedSummary()

        const result = parseUsageDailySummary({
            usageDate: '2026-08-10',
            aggregationZone: 'UTC',
            responses: source.responses,
            usage: source.usage,
            cost: source.cost,
        })

        expect(result.aggregationZone).toBe('UTC')
    })
})
