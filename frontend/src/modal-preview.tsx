import ReactDOM from 'react-dom/client'
import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from './api/modelApi'
import {ModelCatalogVersionModal} from './components/admin/models/ModelCatalogVersionModal'
import {ModelPolicyModal} from './components/admin/models/ModelPolicyModal'
import './index.css'
import './pages/AdminModelsPage.css'
import './pages/AdminModelsPageVisualRefresh.css'

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
    healthStatus: 'AVAILABLE',
    pricingStatus: 'FREE',
    inputUsdPer1mTokens: 0,
    outputUsdPer1mTokens: 0,
    pricingVersion: 'mock-2026-01',
}

const catalogEntry: ModelCatalogEntry = {
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
    inputUsdPer1mTokens: 0,
    cachedInputUsdPer1mTokens: 0,
    cacheWriteInputUsdPer1mTokens: 0,
    outputUsdPer1mTokens: 0,
    extraPricingJson: '{}',
    pricingVersion: 'mock-2026-01',
    effectiveFrom: '2026-09-05T17:45:24Z',
    source: 'MANUAL',
    createdByUserId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T17:40:00Z',
}

const policy: OrganizationModelPolicy = {
    configured: true,
    id: '33333333-3333-4333-8333-333333333333',
    organizationId: '00000000-0000-0000-0000-000000000001',
    version: 1,
    enabled: true,
    allowModelKeys: ['mock:mock-safeai'],
    denyModelKeys: [],
    defaultModelKey: null,
    maxInputTokens: null,
    maxOutputTokens: null,
    maxRequestCostUsd: null,
    monthlyBudgetUsd: null,
    budgetEnforcement: 'SOFT',
    requireCompletePricing: true,
    requireNoTraining: true,
    requireZeroDataRetention: false,
    createdByUserId: null,
    createdAt: '2026-09-05T17:45:24Z',
}

const query = new URLSearchParams(window.location.search)
const modal = query.get('modal') ?? 'policy'

ReactDOM.createRoot(document.getElementById('root')!).render(
    modal === 'catalog'
        ? (
            <ModelCatalogVersionModal
                base={null}
                runtime={runtime}
                catalogByKey={new Map([[catalogEntry.modelKey, catalogEntry]])}
                pending={false}
                onClose={() => undefined}
                onSubmit={async () => undefined}
            />
        )
        : (
            <ModelPolicyModal
                policy={policy}
                catalog={[catalogEntry]}
                effectiveCatalog={[catalogEntry]}
                runtime={runtime}
                organizationId={policy.organizationId}
                organizationName="SafeAI Platform"
                pending={false}
                onClose={() => undefined}
                onPreview={async () => ({
                    runtimeProvider: runtime.provider,
                    runtimeModel: runtime.model,
                    executableModelCount: 1,
                    automaticOutcome: 'ALLOWED',
                    automaticReason: 'POLICY_DEFAULT',
                    wouldLockOutOrganization: false,
                    models: [],
                })}
                onSubmit={async () => undefined}
            />
        ),
)
