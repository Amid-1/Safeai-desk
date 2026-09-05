import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    parseOrganizationModelPolicy,
} from './modelApi'

describe('unconfigured policy wire contract', () => {
    it('keeps synthetic policy explicitly disabled', () => {
        const organizationId =
            'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

        expect(
            parseOrganizationModelPolicy({
                configured: false,
                organizationId,
                version: 0,
                enabled: false,
                allowModelKeys: [],
                denyModelKeys: [],
                budgetEnforcement: 'SOFT',
                requireCompletePricing: false,
                requireNoTraining: false,
                requireZeroDataRetention: false,
            }),
        ).toEqual({
            configured: false,
            id: null,
            organizationId,
            version: 0,
            enabled: false,
            allowModelKeys: [],
            denyModelKeys: [],
            defaultModelKey: null,
            maxInputTokens: null,
            maxOutputTokens: null,
            maxRequestCostUsd: null,
            monthlyBudgetUsd: null,
            budgetEnforcement: 'SOFT',
            requireCompletePricing: false,
            requireNoTraining: false,
            requireZeroDataRetention: false,
            createdByUserId: null,
            createdAt: null,
        })
    })
})
