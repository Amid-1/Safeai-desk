// frontend/src/test/fixtures/modelControlPlaneTestFixtures.ts
/* ============================================================
   frontend/src/test/fixtures/modelControlPlaneTestFixtures.ts

   Shared immutable test fixtures for Model Control Plane tests.
   Keep catalog/runtime/policy defaults in one place so semantic tests
   do not drift and static-analysis tools do not report duplicated setup.
   ============================================================ */

import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../entities/model/api/modelApi'

export function createRuntimeModelStatus(
    overrides: Partial<RuntimeModelStatus> = {},
): RuntimeModelStatus {
    return {
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
        ...overrides,
    }
}

export function createModelCatalogEntry(
    overrides: Partial<ModelCatalogEntry> = {},
): ModelCatalogEntry {
    return {
        id: '00000000-0000-0000-0000-000000000101',
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
        cachedInputUsdPer1mTokens: null,
        cacheWriteInputUsdPer1mTokens: null,
        outputUsdPer1mTokens: '0',
        extraPricingJson: '{}',
        pricingVersion: 'mock-2026-01',
        effectiveFrom: '2026-09-05T17:45:24Z',
        source: 'RUNTIME_IMPORT',
        createdByUserId: '00000000-0000-0000-0000-000000000001',
        createdAt: '2026-09-05T17:45:24Z',
        ...overrides,
    }
}

export function createOrganizationModelPolicy(
    overrides: Partial<OrganizationModelPolicy> = {},
): OrganizationModelPolicy {
    return {
        configured: false,
        id: null,
        organizationId: '00000000-0000-0000-0000-000000000001',
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
        ...overrides,
    }
}
