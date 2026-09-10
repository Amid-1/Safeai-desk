// ============================================================
// frontend/src/api/modelApi.productionHardening.test.ts
// ============================================================
import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    parseModelCatalogEntry,
    parseModelPolicyPreview,
    parseModelRouteDecision,
    parseOrganizationModelPolicy,
    parseRuntimeModelStatus,
} from './modelApi'

const CATALOG = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'openai:gpt-safeai',
    version: 2,
    provider: 'openai',
    providerModelId: 'gpt-safeai',
    displayName: 'SafeAI GPT',
    lifecycle: 'ACTIVE',
    maxInputTokens: 64_000,
    maxOutputTokens: 8_192,
    capabilities: [],
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    retentionStatus: 'STANDARD',
    retentionDays: 30,
    trainingUseStatus: 'CONTRACTUAL_NO_TRAINING',
    pricingStatus: 'CONFIGURED',
    pricingComplete: true,
    inputUsdPer1mTokens: '5',
    cachedInputUsdPer1mTokens: '7',
    cacheWriteInputUsdPer1mTokens: '10',
    outputUsdPer1mTokens: '15',
    extraPricingJson: '{}',
    pricingVersion: 'provider-2026-09',
    effectiveFrom: '2026-09-05T09:00:00Z',
    source: 'MANUAL',
    createdByUserId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T09:00:00Z',
}

const RUNTIME = {
    provider: 'openai',
    model: 'gpt-safeai',
    enabled: true,
    routingMode: 'SINGLE_PROVIDER_STATIC',
    maxInputTokens: 64_000,
    maxOutputTokens: 8_192,
    toolsSupported: false,
    visionSupported: false,
    structuredOutputSupported: false,
    dataRetentionStatus: 'STANDARD',
    healthStatus: 'AVAILABLE',
    pricingStatus: 'CONFIGURED',
    inputUsdPer1mTokens: '5',
    outputUsdPer1mTokens: '15',
    pricingVersion: 'provider-2026-09',
}

const DECISION = {
    id: '55555555-5555-4555-8555-555555555555',
    organizationId: '44444444-4444-4444-8444-444444444444',
    userId: '66666666-6666-4666-8666-666666666666',
    chatId: '77777777-7777-4777-8777-777777777777',
    chatTurnId: '88888888-8888-4888-8888-888888888888',
    clientRequestId: '99999999-9999-4999-8999-999999999999',
    requestContentHash: 'a'.repeat(64),
    requestedModelKey: 'openai:gpt-safeai',
    selectedCatalogEntryId: '11111111-1111-4111-8111-111111111111',
    selectedCatalogVersion: 2,
    selectedModelKey: 'openai:gpt-safeai',
    selectedProvider: 'openai',
    selectedProviderModelId: 'gpt-safeai',
    policyId: '33333333-3333-4333-8333-333333333333',
    policyVersion: 3,
    requiredCapabilities: [],
    inputAccountingVersion: 'UTF8_STRUCTURAL_UNITS_V2',
    additionalInputUnitUpperBound: 0,
    estimatedInputTokens: 10_000,
    estimatedOutputTokens: 4_096,
    estimatedMaxCostUsd: '0.11144',
    monthlyBudgetUsd: '250',
    monthlySpentUsd: '10',
    monthlyProjectedUsd: '10.11144',
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

const UNCONFIGURED_POLICY = {
    configured: false,
    id: null,
    organizationId: '44444444-4444-4444-8444-444444444444',
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
}

const PREVIEW = {
    organizationId: '44444444-4444-4444-8444-444444444444',
    basePolicyVersion: 3,
    enabled: true,
    evaluatedAt: '2026-09-09T17:00:00Z',
    runtimeProvider: 'openai',
    runtimeModel: 'gpt-safeai',
    automaticOutcome: 'ALLOWED',
    automaticReason: 'RUNTIME_ONLY_MATCH',
    automaticModelKey: 'openai:gpt-safeai',
    executableModelCount: 1,
    wouldLockOutOrganization: false,
    models: [
        {
            modelKey: 'openai:gpt-safeai',
            catalogVersion: 2,
            provider: 'openai',
            providerModelId: 'gpt-safeai',
            lifecycle: 'ACTIVE',
            outcome: 'ALLOWED',
            reason: null,
            effectiveInputLimit: 32_000,
            effectiveOutputLimit: 4_096,
            pricingComplete: true,
            estimatedMaxCostUsd: '0.22144',
            monthlyBudgetUsd: '250',
            monthlyProjectedUsd: '10.22144',
            monthlyCostKnown: true,
            budgetExceeded: false,
        },
    ],
}

describe('Model API production hardening', () => {
    it('does not encode provider-specific cache price ordering', () => {
        const parsed = parseModelCatalogEntry(CATALOG)

        expect(parsed.inputUsdPer1mTokens).toBe('5')
        expect(parsed.cachedInputUsdPer1mTokens).toBe('7')
        expect(parsed.cacheWriteInputUsdPer1mTokens).toBe('10')
    })

    it('rejects malformed UUIDs and non-positive catalog versions', () => {
        expect(() => parseModelCatalogEntry({
            ...CATALOG,
            id: 'not-a-uuid',
        })).toThrow(/UUID/)

        expect(() => parseModelCatalogEntry({
            ...CATALOG,
            version: 0,
        })).toThrow(/положительным/)
    })

    it('rejects impossible UTC calendar dates instead of trusting Date.parse coercion', () => {
        expect(() => parseModelCatalogEntry({
            ...CATALOG,
            createdAt: '2026-02-30T09:00:00Z',
        })).toThrow(/корректным ISO-8601 Instant/)
    })

    it('rejects unknown runtime enums and non-positive runtime limits', () => {
        expect(() => parseRuntimeModelStatus({
            ...RUNTIME,
            routingMode: 'MAGIC_ROUTER',
        })).toThrow(/неизвестное значение/)

        expect(() => parseRuntimeModelStatus({
            ...RUNTIME,
            maxInputTokens: 0,
        })).toThrow(/положительным/)
    })

    it('rejects malformed route evidence SHA and unknown accounting versions', () => {
        expect(() => parseModelRouteDecision({
            ...DECISION,
            decisionSha256: 'banana',
        })).toThrow(/SHA-256/)

        expect(() => parseModelRouteDecision({
            ...DECISION,
            inputAccountingVersion: 'UNKNOWN_V99',
        })).toThrow(/неизвестное значение/)
    })

    it('enforces the full synthetic unconfigured policy invariant', () => {
        expect(
            parseOrganizationModelPolicy(UNCONFIGURED_POLICY).enabled,
        ).toBe(false)

        expect(() => parseOrganizationModelPolicy({
            ...UNCONFIGURED_POLICY,
            enabled: true,
        })).toThrow(/некорректное unconfigured состояние/)

        expect(() => parseOrganizationModelPolicy({
            ...UNCONFIGURED_POLICY,
            version: 1,
        })).toThrow(/некорректное unconfigured состояние/)
    })

    it('validates server preview summary and outcome/reason semantics', () => {
        expect(
            parseModelPolicyPreview(PREVIEW).executableModelCount,
        ).toBe(1)

        expect(() => parseModelPolicyPreview({
            ...PREVIEW,
            executableModelCount: 0,
        })).toThrow(/executableModelCount/)

        expect(() => parseModelPolicyPreview({
            ...PREVIEW,
            models: [{
                ...PREVIEW.models[0],
                outcome: 'ALLOWED',
                reason: 'MODEL_DENIED',
            }],
        })).toThrow(/ALLOWED не должен иметь denial reason/)

        expect(() => parseModelPolicyPreview({
            ...PREVIEW,
            automaticOutcome: 'ALLOWED',
            automaticReason: 'MODEL_DENIED',
        })).toThrow(/automaticReason/)
    })
})
