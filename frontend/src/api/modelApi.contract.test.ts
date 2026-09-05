import {
    describe,
    expect,
    it,
} from 'vitest'

import {
    parseModelCatalogEntry,
    parseModelRouteDecision,
    parseOrganizationModelPolicy,
} from './modelApi'

const catalog = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'openai:gpt-safeai',
    version: 2,
    provider: 'openai',
    providerModelId: 'gpt-safeai',
    displayName: 'SafeAI GPT',
    lifecycle: 'ACTIVE',
    maxInputTokens: 64000,
    maxOutputTokens: 8192,
    capabilities: [],
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    retentionStatus: 'STANDARD',
    retentionDays: 30,
    trainingUseStatus: 'CONTRACTUAL_NO_TRAINING',
    pricingStatus: 'CONFIGURED',
    pricingComplete: true,
    inputUsdPer1mTokens: '2.000000000000',
    cachedInputUsdPer1mTokens: '0.500000000000',
    cacheWriteInputUsdPer1mTokens: null,
    outputUsdPer1mTokens: '8.000000000000',
    extraPricingJson: '{}',
    pricingVersion: 'openai-2026-08',
    effectiveFrom: '2026-09-05T09:00:00Z',
    source: 'MANUAL',
    createdByUserId:
        '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T09:00:00Z',
}

const policy = {
    configured: true,
    id: '33333333-3333-4333-8333-333333333333',
    organizationId:
        '44444444-4444-4444-8444-444444444444',
    version: 3,
    enabled: true,
    allowModelKeys: ['openai:gpt-safeai'],
    denyModelKeys: [],
    defaultModelKey: 'openai:gpt-safeai',
    maxInputTokens: 32000,
    maxOutputTokens: 4096,
    maxRequestCostUsd: '1.250000000000',
    monthlyBudgetUsd: '250.000000000000',
    budgetEnforcement: 'HARD',
    requireCompletePricing: true,
    requireNoTraining: true,
    requireZeroDataRetention: false,
    createdByUserId:
        '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T09:00:00Z',
}

const decision = {
    id: '55555555-5555-4555-8555-555555555555',
    organizationId:
        '44444444-4444-4444-8444-444444444444',
    userId:
        '66666666-6666-4666-8666-666666666666',
    chatId:
        '77777777-7777-4777-8777-777777777777',
    chatTurnId:
        '88888888-8888-4888-8888-888888888888',
    clientRequestId:
        '99999999-9999-4999-8999-999999999999',
    requestContentHash: 'a'.repeat(64),
    requestedModelKey: 'openai:gpt-safeai',
    selectedCatalogEntryId:
        '11111111-1111-4111-8111-111111111111',
    selectedCatalogVersion: 2,
    selectedModelKey: 'openai:gpt-safeai',
    selectedProvider: 'openai',
    selectedProviderModelId: 'gpt-safeai',
    policyId:
        '33333333-3333-4333-8333-333333333333',
    policyVersion: 3,
    requiredCapabilities: [],
    inputAccountingVersion:
        'UTF8_STRUCTURAL_UNITS_V2',
    additionalInputUnitUpperBound: 40960,
    estimatedInputTokens: 42000,
    estimatedOutputTokens: 4096,
    estimatedMaxCostUsd: '0.116768000000',
    monthlyBudgetUsd: '250.000000000000',
    monthlySpentUsd: '10.000000000000',
    monthlyProjectedUsd: '10.116768000000',
    monthlyCostKnown: true,
    monthlyCostState: 'KNOWN',
    budgetEnforcement: 'HARD',
    budgetExceeded: false,
    pricingComplete: true,
    outcome: 'ALLOWED',
    reason: 'REQUESTED_MODEL',
    decisionIntegrityVersion: 3,
    decisionSha256: 'b'.repeat(64),
    createdAt: '2026-09-05T09:00:00Z',
}

describe(
    'Model Control Plane API contract',
    () => {
        it(
            'accepts exact decimal strings',
            () => {
                expect(
                    parseModelCatalogEntry(
                        catalog,
                    ).inputUsdPer1mTokens,
                ).toBe(
                    '2.000000000000',
                )

                expect(
                    parseOrganizationModelPolicy(
                        policy,
                    ).monthlyBudgetUsd,
                ).toBe(
                    '250.000000000000',
                )
            },
        )

        it(
            'rejects JSON numeric catalog money',
            () => {
                expect(() =>
                    parseModelCatalogEntry({
                        ...catalog,
                        inputUsdPer1mTokens: 2,
                    }),
                ).toThrow(
                    /exact decimal string/,
                )
            },
        )

        it(
            'rejects JSON numeric policy money',
            () => {
                expect(() =>
                    parseOrganizationModelPolicy({
                        ...policy,
                        monthlyBudgetUsd: 250,
                    }),
                ).toThrow(
                    /exact decimal string/,
                )
            },
        )

        it(
            'accepts REQUESTED_MODEL and integrity v3',
            () => {
                const parsed =
                    parseModelRouteDecision(
                        decision,
                    )

                expect(
                    parsed.reason,
                ).toBe(
                    'REQUESTED_MODEL',
                )

                expect(
                    parsed.decisionIntegrityVersion,
                ).toBe(
                    3,
                )

                expect(
                    parsed.inputAccountingVersion,
                ).toBe(
                    'UTF8_STRUCTURAL_UNITS_V2',
                )
            },
        )

        it(
            'rejects v3 without accounting provenance',
            () => {
                expect(() =>
                    parseModelRouteDecision({
                        ...decision,
                        inputAccountingVersion: null,
                    }),
                ).toThrow(
                    /requires accounting provenance/,
                )
            },
        )

        it(
            'rejects JSON numeric route money',
            () => {
                expect(() =>
                    parseModelRouteDecision({
                        ...decision,
                        estimatedMaxCostUsd: 0.1,
                    }),
                ).toThrow(
                    /exact decimal string/,
                )
            },
        )
    },
)