// frontend/src/features/models/ui/ModelAvailabilitySummary.test.tsx
/* ============================================================
   frontend/src/features/models/ui/ModelAvailabilitySummary.test.tsx
   ============================================================ */
import { describe, expect, it } from 'vitest'
import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../entities/model/api/modelApi'
import { modelConfigurationReadiness, runtimeDataGuarantees } from './modelConfigurationReadiness'

// Complete contract fixtures: missing required fields must fail TypeScript
// compilation rather than being hidden by assertions or unknown casts.
const ORGANIZATION_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

const runtime: RuntimeModelStatus = {
    provider: 'mock',
    model: 'mock-safeai',
    enabled: true,
    routingMode: 'SINGLE_PROVIDER_STATIC',
    maxInputTokens: 64_000,
    maxOutputTokens: 2_048,
    toolsSupported: false,
    visionSupported: false,
    structuredOutputSupported: false,
    dataRetentionStatus: 'NOT_DECLARED',
    healthStatus: 'NOT_PROBED',
    pricingStatus: 'FREE',
    inputUsdPer1mTokens: '0',
    outputUsdPer1mTokens: '0',
    pricingVersion: 'mock-2026-01',
}

const model: ModelCatalogEntry = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'mock:mock-safeai',
    version: 1,
    provider: 'mock',
    providerModelId: 'mock-safeai',
    displayName: 'mock-safeai',
    lifecycle: 'ACTIVE',
    maxInputTokens: 64_000,
    maxOutputTokens: 2_048,
    capabilities: [],
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    retentionStatus: 'NOT_DECLARED',
    retentionDays: null,
    trainingUseStatus: 'NOT_DECLARED',
    pricingStatus: 'FREE',
    pricingComplete: true,
    inputUsdPer1mTokens: '0',
    cachedInputUsdPer1mTokens: '0',
    cacheWriteInputUsdPer1mTokens: '0',
    outputUsdPer1mTokens: '0',
    extraPricingJson: '{}',
    pricingVersion: 'mock-2026-01',
    effectiveFrom: '2026-09-05T10:00:00Z',
    source: 'RUNTIME_IMPORT',
    createdByUserId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T10:00:00Z',
}

const policy: OrganizationModelPolicy = {
    configured: true,
    id: '33333333-3333-4333-8333-333333333333',
    organizationId: ORGANIZATION_ID,
    version: 1,
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
    createdByUserId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T10:00:00Z',
}

describe('Model Control Plane configuration summary (non-authoritative)', () => {
    it('does not confirm guarantees with missing, expired or incompatible evidence', () => {
        const now = Date.parse('2026-11-19T12:00:00Z')
        const dataAssurance = {noTraining: true, zeroDataRetention: true, source: 'CONTRACT-42',
            verifiedAt: '2026-11-01T00:00:00Z', expiresAt: '2026-12-01T00:00:00Z'}
        const external = {...runtime, provider: 'openai', dataAssurance}
        expect(runtimeDataGuarantees(external, now)).toEqual({noTraining: true, zeroDataRetention: true})
        expect(runtimeDataGuarantees(external, Date.parse('2026-12-01T00:00:00Z'))).toEqual({noTraining: false, zeroDataRetention: false})
        expect(runtimeDataGuarantees({...external, dataAssurance: {...dataAssurance, source: null}}, now).noTraining).toBe(false)
        expect(runtimeDataGuarantees({...external, dataAssurance: {...dataAssurance, source: 'LOCAL_MOCK'}}, now).zeroDataRetention).toBe(false)
        expect(runtimeDataGuarantees({...runtime, dataAssurance: {...dataAssurance, source: 'LOCAL_MOCK', verifiedAt: null, expiresAt: null}}, now).noTraining).toBe(true)
        expect(runtimeDataGuarantees(runtime, now).zeroDataRetention).toBe(false)
    })
    it('reports unconfirmed catalog assurances and incomplete pricing even with local evidence', () => {
        const result = modelConfigurationReadiness({...runtime, dataAssurance: {
            noTraining: true, zeroDataRetention: true, source: 'LOCAL_MOCK', verifiedAt: null, expiresAt: null,
        }}, [{...model, pricingComplete: false}], {...policy, requireNoTraining: true,
            requireZeroDataRetention: true, requireCompletePricing: true})
        expect(result.blockers).toEqual(expect.arrayContaining([
            'В действующих записях каталога не подтверждён запрет обучения.',
            'В действующих записях каталога не подтверждено нулевое хранение.',
            'В действующих записях каталога отсутствуют полные сведения о стоимости.',
        ]))
    })
    it('does not claim a model is executable just because runtime and catalog match', () => {
        expect(modelConfigurationReadiness(runtime, [model], policy)).toEqual({
            compatibleCount: 1,
            allowedCount: 1,
            policyEnabled: true,
            runtimeEnabled: true,
            blockers: [],
        })
    })

    it('shows missing effective catalog match, not latest snapshot', () => {
        const result = modelConfigurationReadiness(runtime, [
            { ...model, providerModelId: 'a-different-model' },
        ], policy)

        expect(result.compatibleCount).toBe(0)
        expect(result.blockers.join(' ')).toContain('действующей версии')
    })

    it('does not match the same model ID under a different provider', () => {
        const result = modelConfigurationReadiness(runtime, [
            { ...model, provider: 'another-provider' },
        ], policy)

        expect(result.compatibleCount).toBe(0)
        expect(result.blockers).toHaveLength(1)
    })

    it('reports disabled runtime and server-known unavailability separately', () => {
        const result = modelConfigurationReadiness(
            { ...runtime, enabled: false, healthStatus: 'UNAVAILABLE' },
            [model],
            policy,
        )

        expect(result.runtimeEnabled).toBe(false)
        expect(result.blockers).toHaveLength(2)
        expect(result.blockers.join(' ')).toContain('Подключённая модель отключена')
        expect(result.blockers.join(' ')).toContain('неуспешно')
    })

    it('distinguishes disabled policy from an enabled allowlist exclusion', () => {
        const restricted: OrganizationModelPolicy = {
            ...policy,
            allowModelKeys: ['other:model'],
        }

        expect(modelConfigurationReadiness(runtime, [model], restricted).blockers)
            .toHaveLength(1)
        expect(modelConfigurationReadiness(
            runtime,
            [model],
            { ...restricted, enabled: false },
        ).blockers).toHaveLength(0)
    })

    it('matches allowlist keys without changing their case in stored policy', () => {
        const restricted: OrganizationModelPolicy = {
            ...policy,
            allowModelKeys: ['MOCK:MOCK-SAFEAI'],
        }

        expect(modelConfigurationReadiness(runtime, [model], restricted).blockers)
            .toHaveLength(0)
        expect(restricted.allowModelKeys).toEqual(['MOCK:MOCK-SAFEAI'])
    })

    it('gives denylist precedence over allowlist even with different case', () => {
        const restricted: OrganizationModelPolicy = {
            ...policy,
            allowModelKeys: [model.modelKey],
            denyModelKeys: ['MOCK:MOCK-SAFEAI'],
        }

        expect(modelConfigurationReadiness(runtime, [model], restricted).blockers)
            .toHaveLength(1)
    })

    it.each(['RETIRED', 'DISABLED'] as const)(
        'excludes %s catalog entries from effective matching',
        (lifecycle) => {
            const result = modelConfigurationReadiness(
                runtime,
                [{ ...model, lifecycle }],
                policy,
            )

            expect(result.compatibleCount).toBe(0)
            expect(result.blockers.join(' ')).toContain('действующей версии')
        },
    )

    it('keeps a DEPRECATED effective entry eligible for configuration matching', () => {
        const result = modelConfigurationReadiness(
            runtime,
            [{ ...model, lifecycle: 'DEPRECATED' }],
            policy,
        )

        expect(result.compatibleCount).toBe(1)
        expect(result.blockers).toEqual([])
    })
})
