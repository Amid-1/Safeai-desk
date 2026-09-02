import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    createModelCatalogVersion,
    createOrganizationModelPolicyVersion,
    getEffectiveModelCatalog,
    getModelCatalog,
    getModelRouteDecision,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
} from './modelApi'
import {
    apiRequest,
} from './http'

vi.mock('./http', () => ({
    API_TIMEOUTS: {default: 30_000},
    apiRequest: vi.fn(),
}))

const CATALOG_ENTRY = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'openai:gpt-safeai',
    version: 2,
    provider: 'openai',
    providerModelId: 'gpt-safeai',
    displayName: 'SafeAI GPT',
    lifecycle: 'ACTIVE',
    maxInputTokens: 64000,
    maxOutputTokens: 8192,
    capabilities: ['TOOLS', 'STRUCTURED_OUTPUT'],
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    retentionStatus: 'STANDARD',
    retentionDays: 30,
    trainingUseStatus: 'CONTRACTUAL_NO_TRAINING',
    pricingStatus: 'CONFIGURED',
    pricingComplete: true,
    inputUsdPer1mTokens: '2',
    cachedInputUsdPer1mTokens: '0.5',
    cacheWriteInputUsdPer1mTokens: null,
    outputUsdPer1mTokens: '8',
    extraPricingJson: '{}',
    pricingVersion: 'openai-2026-08',
    effectiveFrom: '2026-08-28T12:00:00Z',
    source: 'MANUAL',
    createdByUserId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-08-28T12:00:00Z',
}

const POLICY = {
    configured: true,
    id: '33333333-3333-4333-8333-333333333333',
    organizationId: '44444444-4444-4444-8444-444444444444',
    version: 3,
    enabled: true,
    allowModelKeys: ['openai:gpt-safeai'],
    denyModelKeys: [],
    defaultModelKey: 'openai:gpt-safeai',
    maxInputTokens: 32000,
    maxOutputTokens: 4096,
    maxRequestCostUsd: '1.25',
    monthlyBudgetUsd: '250',
    budgetEnforcement: 'HARD',
    requireCompletePricing: true,
    requireNoTraining: true,
    requireZeroDataRetention: false,
    createdByUserId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-08-28T13:00:00Z',
}

describe('modelApi contract', () => {
    it('parses exact string runtime pricing without IEEE-754 loss', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce({
            provider: 'openai',
            model: 'gpt-safeai',
            enabled: true,
            routingMode: 'SINGLE_PROVIDER_STATIC',
            maxInputTokens: 64000,
            maxOutputTokens: 2048,
            toolsSupported: false,
            visionSupported: false,
            structuredOutputSupported: false,
            dataRetentionStatus: 'NOT_DECLARED',
            healthStatus: 'NOT_PROBED',
            pricingStatus: 'CONFIGURED',
            inputUsdPer1mTokens: '2',
            outputUsdPer1mTokens: '8',
            pricingVersion: 'openai-2026-08',
        })

        await expect(
            getRuntimeModelStatus(),
        ).resolves.toMatchObject({
            provider: 'openai',
            inputUsdPer1mTokens: '2',
            outputUsdPer1mTokens: '8',
        })
    })

    it('loads and strictly parses latest catalog snapshots', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce([
            CATALOG_ENTRY,
        ])

        await expect(
            getModelCatalog(),
        ).resolves.toEqual([
            expect.objectContaining({
                modelKey: 'openai:gpt-safeai',
                version: 2,
                inputUsdPer1mTokens: '2',
                cachedInputUsdPer1mTokens: '0.5',
                outputUsdPer1mTokens: '8',
            }),
        ])

        expect(apiRequest).toHaveBeenCalledWith(
            '/api/admin/models/catalog',
            expect.objectContaining({method: 'GET'}),
        )
    })

    it('loads the effective catalog snapshot from the explicit as-of-now endpoint', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce([
            CATALOG_ENTRY,
        ])

        await expect(
            getEffectiveModelCatalog(),
        ).resolves.toEqual([
            expect.objectContaining({
                id: CATALOG_ENTRY.id,
                modelKey: CATALOG_ENTRY.modelKey,
                version: 2,
            }),
        ])

        expect(apiRequest).toHaveBeenCalledWith(
            '/api/admin/models/catalog/effective',
            expect.objectContaining({method: 'GET'}),
        )
    })

    it('sends catalog version creation to the canonical endpoint', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce(
            CATALOG_ENTRY,
        )

        await createModelCatalogVersion({
            modelKey: 'openai:gpt-safeai',
            provider: 'openai',
            providerModelId: 'gpt-safeai',
            displayName: 'SafeAI GPT',
            lifecycle: 'ACTIVE',
            maxInputTokens: 64000,
            maxOutputTokens: 8192,
            capabilities: ['TOOLS'],
            inputModalities: ['TEXT'],
            outputModalities: ['TEXT'],
            retentionStatus: 'STANDARD',
            retentionDays: 30,
            trainingUseStatus: 'CONTRACTUAL_NO_TRAINING',
            pricingStatus: 'CONFIGURED',
            pricingComplete: true,
            inputUsdPer1mTokens: '2',
            cachedInputUsdPer1mTokens: '0.5',
            cacheWriteInputUsdPer1mTokens: null,
            outputUsdPer1mTokens: '8',
            extraPricingJson: '{}',
            pricingVersion: 'openai-2026-08',
            effectiveFrom: null,
            expectedPreviousVersion: 1,
        })

        expect(apiRequest).toHaveBeenCalledWith(
            '/api/admin/models/catalog',
            expect.objectContaining({
                method: 'POST',
                json: expect.objectContaining({
                    expectedPreviousVersion: 1,
                }),
            }),
        )
    })

    it('imports the physical runtime through the explicit bootstrap endpoint', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce(
            CATALOG_ENTRY,
        )

        await importRuntimeModelCatalog()

        expect(apiRequest).toHaveBeenCalledWith(
            '/api/admin/models/catalog/import-runtime',
            expect.objectContaining({method: 'POST'}),
        )
    })

    it('loads and updates a tenant policy with version evidence', async () => {
        vi.mocked(apiRequest)
            .mockResolvedValueOnce(POLICY)
            .mockResolvedValueOnce({
                ...POLICY,
                version: 4,
            })

        const organizationId =
            '44444444-4444-4444-8444-444444444444'

        await expect(
            getOrganizationModelPolicy(
                organizationId,
            ),
        ).resolves.toMatchObject({
            version: 3,
            monthlyBudgetUsd: '250',
        })

        await createOrganizationModelPolicyVersion(
            organizationId,
            {
                expectedPreviousVersion: 3,
                enabled: true,
                allowModelKeys: [
                    'openai:gpt-safeai',
                ],
                denyModelKeys: [],
                defaultModelKey: 'openai:gpt-safeai',
                maxInputTokens: 32000,
                maxOutputTokens: 4096,
                maxRequestCostUsd: '1.25',
                monthlyBudgetUsd: '250',
                budgetEnforcement: 'HARD',
                requireCompletePricing: true,
                requireNoTraining: true,
                requireZeroDataRetention: false,
            },
        )

        expect(apiRequest).toHaveBeenLastCalledWith(
            `/api/admin/models/policies/${organizationId}`,
            expect.objectContaining({
                method: 'POST',
                json: expect.objectContaining({
                    expectedPreviousVersion: 3,
                }),
            }),
        )
    })

    it('parses immutable route decision evidence including unknown monthly cost', async () => {
        const decisionId =
            '55555555-5555-4555-8555-555555555555'

        vi.mocked(apiRequest).mockResolvedValueOnce({
            id: decisionId,
            organizationId: '44444444-4444-4444-8444-444444444444',
            userId: '22222222-2222-4222-8222-222222222222',
            chatId: '66666666-6666-4666-8666-666666666666',
            chatTurnId: null,
            clientRequestId: '77777777-7777-4777-8777-777777777777',
            requestContentHash: 'a'.repeat(64),
            requestedModelKey: 'openai:gpt-safeai',
            selectedCatalogEntryId: null,
            selectedCatalogVersion: null,
            selectedModelKey: 'openai:gpt-safeai',
            selectedProvider: 'openai',
            selectedProviderModelId: 'gpt-safeai',
            policyId: POLICY.id,
            policyVersion: 3,
            requiredCapabilities: ['TOOLS'],
            estimatedInputTokens: 100,
            estimatedOutputTokens: 1000,
            estimatedMaxCostUsd: null,
            monthlyBudgetUsd: '250',
            monthlySpentUsd: '20',
            monthlyProjectedUsd: null,
            monthlyCostKnown: false,
            monthlyCostState: 'UNKNOWN',
            budgetEnforcement: 'HARD',
            budgetExceeded: false,
            pricingComplete: false,
            outcome: 'DENIED',
            reason: 'MONTHLY_BUDGET_UNVERIFIABLE',
            decisionIntegrityVersion: 2,
            decisionSha256: 'b'.repeat(64),
            createdAt: '2026-08-28T14:00:00Z',
        })

        await expect(
            getModelRouteDecision(decisionId),
        ).resolves.toMatchObject({
            id: decisionId,
            outcome: 'DENIED',
            monthlyCostKnown: false,
            monthlyBudgetUsd: '250',
        })
    })


    it('parses unconfigured policy when Jackson NON_NULL omits every nullable field', async () => {
        const organizationId =
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

        vi.mocked(apiRequest).mockResolvedValueOnce({
            configured: false,
            organizationId,
            version: 0,
            enabled: true,
            allowModelKeys: [],
            denyModelKeys: [],
            budgetEnforcement: 'SOFT',
            requireCompletePricing: false,
            requireNoTraining: false,
            requireZeroDataRetention: false,
        })

        await expect(
            getOrganizationModelPolicy(
                organizationId,
            ),
        ).resolves.toEqual({
            configured: false,
            id: null,
            organizationId,
            version: 0,
            enabled: true,
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

    it('parses UNPRICED runtime when nullable pricing fields are omitted', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce({
            provider: 'mock',
            model: 'mock-safeai',
            enabled: true,
            routingMode: 'SINGLE_PROVIDER_STATIC',
            maxInputTokens: 64000,
            maxOutputTokens: 2048,
            toolsSupported: false,
            visionSupported: false,
            structuredOutputSupported: false,
            dataRetentionStatus: 'NOT_DECLARED',
            healthStatus: 'NOT_PROBED',
            pricingStatus: 'UNPRICED',
        })

        await expect(
            getRuntimeModelStatus(),
        ).resolves.toMatchObject({
            inputUsdPer1mTokens: null,
            outputUsdPer1mTokens: null,
            pricingVersion: null,
        })
    })

    it('parses catalog nullable columns omitted by Jackson NON_NULL', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce([
            {
                id: '11111111-1111-4111-8111-111111111111',
                modelKey: 'mock:mock-safeai',
                version: 1,
                provider: 'mock',
                providerModelId: 'mock-safeai',
                displayName: 'mock-safeai',
                lifecycle: 'ACTIVE',
                maxInputTokens: 64000,
                maxOutputTokens: 2048,
                capabilities: [],
                inputModalities: ['TEXT'],
                outputModalities: ['TEXT'],
                retentionStatus: 'NOT_DECLARED',
                trainingUseStatus: 'NOT_DECLARED',
                pricingStatus: 'UNPRICED',
                pricingComplete: false,
                extraPricingJson: '{}',
                effectiveFrom: '2026-08-29T12:00:00Z',
                source: 'RUNTIME_IMPORT',
                createdByUserId:
                    '22222222-2222-4222-8222-222222222222',
                createdAt: '2026-08-29T12:00:00Z',
            },
        ])

        await expect(
            getModelCatalog(),
        ).resolves.toEqual([
            expect.objectContaining({
                retentionDays: null,
                inputUsdPer1mTokens: null,
                cachedInputUsdPer1mTokens: null,
                cacheWriteInputUsdPer1mTokens: null,
                outputUsdPer1mTokens: null,
                pricingVersion: null,
            }),
        ])
    })

    it('parses denied route evidence with omitted nullable fields', async () => {
        const decisionId =
            '55555555-5555-4555-8555-555555555555'

        vi.mocked(apiRequest).mockResolvedValueOnce({
            id: decisionId,
            organizationId:
                '44444444-4444-4444-8444-444444444444',
            userId:
                '22222222-2222-4222-8222-222222222222',
            chatId:
                '66666666-6666-4666-8666-666666666666',
            clientRequestId:
                '77777777-7777-4777-8777-777777777777',
            requestContentHash: 'a'.repeat(64),
            requiredCapabilities: [],
            monthlyCostKnown: true,
            monthlyCostState: 'NOT_EVALUATED',
            budgetExceeded: false,
            pricingComplete: false,
            outcome: 'DENIED',
            reason: 'MODEL_NOT_FOUND',
            decisionIntegrityVersion: 2,
            decisionSha256: 'b'.repeat(64),
            createdAt: '2026-08-29T12:00:00Z',
        })

        await expect(
            getModelRouteDecision(decisionId),
        ).resolves.toMatchObject({
            chatTurnId: null,
            requestedModelKey: null,
            selectedCatalogEntryId: null,
            selectedCatalogVersion: null,
            selectedModelKey: null,
            selectedProvider: null,
            selectedProviderModelId: null,
            policyId: null,
            policyVersion: null,
            estimatedInputTokens: null,
            estimatedOutputTokens: null,
            estimatedMaxCostUsd: null,
            monthlyBudgetUsd: null,
            monthlySpentUsd: null,
            monthlyProjectedUsd: null,
            budgetEnforcement: null,
        })
    })

    it('rejects unknown governance enum values', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce({
            ...CATALOG_ENTRY,
            lifecycle: 'MAGIC',
        })

        await expect(
            getModelCatalog(),
        ).rejects.toThrow(
            'modelCatalog[0].lifecycle содержит неизвестное значение',
        )
    })

    it('rejects JSON numbers for money because precision may already be lost', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce({
            provider: 'openai',
            model: 'gpt-safeai',
            enabled: true,
            routingMode: 'SINGLE_PROVIDER_STATIC',
            maxInputTokens: 64000,
            maxOutputTokens: 2048,
            toolsSupported: false,
            visionSupported: false,
            structuredOutputSupported: false,
            dataRetentionStatus: 'NOT_DECLARED',
            healthStatus: 'NOT_PROBED',
            pricingStatus: 'CONFIGURED',
            inputUsdPer1mTokens: 2,
            outputUsdPer1mTokens: '8',
            pricingVersion: 'openai-2026-08',
        })

        await expect(getRuntimeModelStatus()).rejects.toThrow(
            'runtimeModel.inputUsdPer1mTokens должен быть decimal string',
        )
    })

    it('fails closed on semantically malformed ALLOWED evidence', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce({
            id: '55555555-5555-4555-8555-555555555555',
            organizationId: '44444444-4444-4444-8444-444444444444',
            userId: '22222222-2222-4222-8222-222222222222',
            chatId: '66666666-6666-4666-8666-666666666666',
            chatTurnId: null,
            clientRequestId: '77777777-7777-4777-8777-777777777777',
            requestContentHash: 'a'.repeat(64),
            selectedModelKey: 'openai:gpt-safeai',
            selectedProvider: 'openai',
            selectedProviderModelId: 'gpt-safeai',
            requiredCapabilities: [],
            estimatedInputTokens: 100,
            estimatedOutputTokens: 1000,
            monthlyCostKnown: false,
            monthlyCostState: 'NOT_EVALUATED',
            budgetExceeded: false,
            pricingComplete: true,
            outcome: 'ALLOWED',
            reason: 'REQUESTED_MODEL',
            decisionIntegrityVersion: 2,
            decisionSha256: 'b'.repeat(64),
            createdAt: '2026-08-28T14:00:00Z',
        })

        await expect(
            getModelRouteDecision('55555555-5555-4555-8555-555555555555'),
        ).rejects.toThrow('ALLOWED decision не содержит executable metadata')
    })

})
