// frontend/src/entities/model/api/modelApi.refactor.test.ts
// Регрессионные тесты публичной поверхности, HTTP-контрактов и строгих проверок после разделения модуля.

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import * as modelApi from './modelApi'
import {
    getRuntimeModelStatus,
    probeRuntimeModel,
    previewOrganizationModelPolicy,
    parseModelCatalogEntry,
    parseModelPolicyPreview,
    parseOrganizationModelPolicy,
    parseRuntimeModelProbe,
} from './modelApi'

vi.mock('../../../shared/api/http', async (importOriginal) => {
    const original = await importOriginal<typeof import('../../../shared/api/http')>()
    return { ...original, apiRequest: vi.fn() }
})

const ORG_ID = '44444444-4444-4444-8444-444444444444'
const USER_ID = '22222222-2222-4222-8222-222222222222'

const RUNTIME = {
    provider: 'openai',
    model: 'safeai-model',
    enabled: true,
    routingMode: 'SINGLE_PROVIDER_STATIC',
    maxInputTokens: 64_000,
    maxOutputTokens: 4096,
    toolsSupported: false,
    visionSupported: false,
    structuredOutputSupported: false,
    dataRetentionStatus: 'NOT_DECLARED',
    healthStatus: 'NOT_PROBED',
    pricingStatus: 'UNPRICED',
    inputUsdPer1mTokens: null,
    outputUsdPer1mTokens: null,
    pricingVersion: null,
} as const

const PROBE = {
    provider: 'openai',
    model: 'safeai-model',
    status: 'AVAILABLE',
    checkedAt: '2026-10-21T07:00:00Z',
    latencyMs: 0,
    httpStatus: 200,
    message: '',
} as const

const CATALOG = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'openai:safeai-model',
    version: 1,
    provider: 'openai',
    providerModelId: 'safeai-model',
    displayName: 'SafeAI model',
    lifecycle: 'ACTIVE',
    maxInputTokens: 64_000,
    maxOutputTokens: 4096,
    capabilities: [] as string[],
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    retentionStatus: 'STANDARD',
    retentionDays: 30,
    trainingUseStatus: 'NOT_USED',
    pricingStatus: 'CONFIGURED',
    pricingComplete: true,
    inputUsdPer1mTokens: '0.000000000001',
    cachedInputUsdPer1mTokens: null,
    cacheWriteInputUsdPer1mTokens: null,
    outputUsdPer1mTokens: '123456789012345678.123456789012',
    extraPricingJson: '{}',
    pricingVersion: '2026-10',
    effectiveFrom: '2026-10-21T07:00:00Z',
    source: 'MANUAL',
    createdByUserId: USER_ID,
    createdAt: '2026-10-21T07:00:00Z',
} as const

const POLICY = {
    configured: false,
    organizationId: ORG_ID,
    version: 0,
    enabled: false,
    allowModelKeys: [] as string[],
    denyModelKeys: [] as string[],
    budgetEnforcement: 'SOFT',
    requireCompletePricing: false,
    requireNoTraining: false,
    requireZeroDataRetention: false,
} as const

const POLICY_PREVIEW = {
    organizationId: ORG_ID,
    basePolicyVersion: 0,
    enabled: false,
    evaluatedAt: '2026-10-21T07:00:00Z',
    runtimeProvider: 'openai',
    runtimeModel: 'safeai-model',
    automaticOutcome: 'DENIED',
    automaticReason: 'MODEL_NOT_ALLOWED',
    automaticModelKey: null,
    executableModelCount: 0,
    wouldLockOutOrganization: false,
    models: [
        {
            modelKey: 'openai:safeai-model',
            catalogVersion: 1,
            provider: 'openai',
            providerModelId: 'safeai-model',
            lifecycle: 'ACTIVE',
            outcome: 'DENIED',
            reason: 'MODEL_NOT_ALLOWED',
            effectiveInputLimit: 64_000,
            effectiveOutputLimit: 4096,
            pricingComplete: false,
            estimatedMaxCostUsd: null,
            monthlyBudgetUsd: null,
            monthlyProjectedUsd: null,
            monthlyCostKnown: false,
            budgetExceeded: false,
        },
    ],
} as const

const PREVIEW_REQUEST = {
    expectedPreviousVersion: 0,
    enabled: false,
    allowModelKeys: [] as string[],
    denyModelKeys: [] as string[],
    defaultModelKey: null,
    maxInputTokens: null,
    maxOutputTokens: null,
    maxRequestCostUsd: null,
    monthlyBudgetUsd: null,
    budgetEnforcement: 'SOFT' as const,
    requireCompletePricing: false,
    requireNoTraining: false,
    requireZeroDataRetention: false,
}

beforeEach(() => {
    vi.resetAllMocks()
})

describe('modelApi: совместимость после декомпозиции', () => {
    it('не изменяет список публичных runtime-экспортов', () => {
        const exported = [
            'RUNTIME_MODEL_PROBE_STATUSES', 'RUNTIME_ROUTING_MODES',
            'RUNTIME_DATA_RETENTION_STATUSES', 'RUNTIME_HEALTH_STATUSES',
            'RUNTIME_PRICING_STATUSES', 'INPUT_ACCOUNTING_VERSIONS',
            'MODEL_CAPABILITIES', 'MODEL_MODALITIES', 'MODEL_LIFECYCLES',
            'MODEL_RETENTION_STATUSES', 'MODEL_TRAINING_USE_STATUSES',
            'MODEL_PRICING_STATUSES', 'MODEL_CATALOG_SOURCES',
            'BUDGET_ENFORCEMENTS', 'MONTHLY_COST_STATES',
            'MODEL_ROUTE_OUTCOMES', 'MODEL_ROUTE_REASONS',
            'getRuntimeModelStatus', 'probeRuntimeModel',
            'getModelCatalog', 'getEffectiveModelCatalog',
            'createModelCatalogVersion', 'importRuntimeModelCatalog',
            'getOrganizationModelPolicy',
            'createOrganizationModelPolicyVersion',
            'previewOrganizationModelPolicy', 'getModelRouteDecision',
            'parseRuntimeModelStatus', 'parseRuntimeModelProbe',
            'parseModelCatalogEntry', 'parseOrganizationModelPolicy',
            'parseModelRouteDecision', 'parseModelPolicyPreview',
        ]
        expect(Object.keys(modelApi).sort()).toEqual(exported.sort())
    })

    it('сохраняет GET runtime, signal и строгий парсинг nullable-цен', async () => {
        const controller = new AbortController()
        vi.mocked(apiRequest).mockResolvedValueOnce(RUNTIME)

        await expect(getRuntimeModelStatus(controller.signal)).resolves.toEqual(RUNTIME)
        expect(apiRequest).toHaveBeenCalledWith('/api/admin/models/runtime', {
            method: 'GET',
            signal: controller.signal,
        })
    })

    it('выполняет probe через POST без отправки тела и сохраняет signal', async () => {
        const controller = new AbortController()
        vi.mocked(apiRequest).mockResolvedValueOnce(PROBE)

        await expect(probeRuntimeModel({ signal: controller.signal })).resolves.toEqual(PROBE)
        expect(apiRequest).toHaveBeenCalledWith('/api/admin/models/runtime/probe', {
            method: 'POST',
            signal: controller.signal,
        })
    })

    it('сохраняет preview-контракт и отправляет запрос без модификации', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce(POLICY_PREVIEW)

        await expect(previewOrganizationModelPolicy(ORG_ID, PREVIEW_REQUEST))
            .resolves.toEqual(POLICY_PREVIEW)
        expect(apiRequest).toHaveBeenCalledWith(
            `/api/admin/models/policies/${ORG_ID}/preview`,
            { method: 'POST', json: PREVIEW_REQUEST, signal: undefined },
        )
    })

    it('отсекает некорректный UUID до любого сетевого запроса', async () => {
        await expect(previewOrganizationModelPolicy('not-uuid', PREVIEW_REQUEST))
            .rejects.toThrow(/UUID/)
        expect(apiRequest).not.toHaveBeenCalled()
    })

    it('не преобразует денежные decimal-строки через Number', () => {
        expect(parseModelCatalogEntry(CATALOG).outputUsdPer1mTokens)
            .toBe('123456789012345678.123456789012')
        expect(parseModelCatalogEntry(CATALOG).inputUsdPer1mTokens)
            .toBe('0.000000000001')
    })

    it.each([
        { ...CATALOG, outputUsdPer1mTokens: 0.1 },
        { ...CATALOG, outputUsdPer1mTokens: '-1' },
        { ...CATALOG, outputUsdPer1mTokens: '1e10' },
        { ...CATALOG, outputUsdPer1mTokens: '0.1234567890123' },
        { ...CATALOG, outputUsdPer1mTokens: '1234567890123456789' },
        { ...CATALOG, pricingComplete: false },
        { ...CATALOG, inputModalities: [] },
        { ...CATALOG, createdAt: '2026-02-30T07:00:00Z' },
    ])('не принимает некорректный каталог', (invalid) => {
        expect(() => parseModelCatalogEntry(invalid)).toThrow()
    })

    it('сохраняет nullable-поля unconfigured policy, не превращая отсутствие в разрешение', () => {
        const result = parseOrganizationModelPolicy(POLICY)
        expect(result).toMatchObject({
            configured: false,
            enabled: false,
            id: null,
            defaultModelKey: null,
            monthlyBudgetUsd: null,
        })
        expect(() => parseOrganizationModelPolicy({ ...POLICY, enabled: true })).toThrow()
    })

    it('запрещает противоречивые поля preview и повторяющиеся modelKey', () => {
        expect(parseModelPolicyPreview(POLICY_PREVIEW).executableModelCount).toBe(0)
        expect(() => parseModelPolicyPreview({
            ...POLICY_PREVIEW,
            executableModelCount: 1,
        })).toThrow()
        expect(() => parseModelPolicyPreview({
            ...POLICY_PREVIEW,
            models: [POLICY_PREVIEW.models[0], POLICY_PREVIEW.models[0]],
        })).toThrow()
        expect(() => parseModelPolicyPreview({
            ...POLICY_PREVIEW,
            automaticOutcome: 'ALLOWED',
        })).toThrow()
    })

    it.each([
        { ...PROBE, status: 'UNKNOWN_STATUS' },
        { ...PROBE, latencyMs: -1 },
        { ...PROBE, latencyMs: Number.MAX_SAFE_INTEGER + 1 },
        { ...PROBE, httpStatus: 99 },
        { ...PROBE, httpStatus: 600 },
        { ...PROBE, checkedAt: '2026-13-01T07:00:00Z' },
    ])('не принимает повреждённый probe', (invalid) => {
        expect(() => parseRuntimeModelProbe(invalid)).toThrow()
    })
})
