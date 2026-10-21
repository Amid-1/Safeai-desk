// frontend/src/api/model/modelTypes.ts
// Назначение: контракты моделей, политик, маршрутизации и цен.

import type {
    MODEL_CAPABILITIES,
    MODEL_MODALITIES,
    MODEL_LIFECYCLES,
    MODEL_RETENTION_STATUSES,
    MODEL_TRAINING_USE_STATUSES,
    MODEL_PRICING_STATUSES,
    MODEL_CATALOG_SOURCES,
    BUDGET_ENFORCEMENTS,
    MODEL_ROUTE_OUTCOMES,
    MONTHLY_COST_STATES,
    RUNTIME_ROUTING_MODES,
    RUNTIME_DATA_RETENTION_STATUSES,
    RUNTIME_HEALTH_STATUSES,
    RUNTIME_PRICING_STATUSES,
    INPUT_ACCOUNTING_VERSIONS,
    RUNTIME_MODEL_PROBE_STATUSES,
} from './modelEnums'

export type ModelCapability =
    typeof MODEL_CAPABILITIES[number]

export type ModelModality =
    typeof MODEL_MODALITIES[number]

export type ModelLifecycle =
    typeof MODEL_LIFECYCLES[number]

export type ModelRetentionStatus =
    typeof MODEL_RETENTION_STATUSES[number]

export type ModelTrainingUseStatus =
    typeof MODEL_TRAINING_USE_STATUSES[number]

export type ModelPricingStatus =
    typeof MODEL_PRICING_STATUSES[number]

export type ModelCatalogSource =
    typeof MODEL_CATALOG_SOURCES[number]

export type BudgetEnforcement =
    typeof BUDGET_ENFORCEMENTS[number]

export type ModelRouteOutcome =
    typeof MODEL_ROUTE_OUTCOMES[number]

export type MonthlyCostState =
    typeof MONTHLY_COST_STATES[number]

export type ModelRouteReason =
    'REQUESTED_MODEL'
    | 'POLICY_DEFAULT'
    | 'RUNTIME_ONLY_MATCH'
    | 'LEGACY_RUNTIME_FALLBACK'
    | 'MODEL_NOT_ALLOWED'
    | 'MODEL_DENIED'
    | 'MODEL_NOT_FOUND'
    | 'AMBIGUOUS_RUNTIME_MAPPING'
    | 'MODEL_DISABLED'
    | 'RUNTIME_MISMATCH'
    | 'CAPABILITY_UNSUPPORTED'
    | 'INPUT_LIMIT_EXCEEDED'
    | 'OUTPUT_LIMIT_EXCEEDED'
    | 'PRICING_INCOMPLETE'
    | 'TRAINING_POLICY_UNSATISFIED'
    | 'RETENTION_POLICY_UNSATISFIED'
    | 'REQUEST_COST_LIMIT_EXCEEDED'
    | 'MONTHLY_BUDGET_EXCEEDED'
    | 'MONTHLY_BUDGET_UNVERIFIABLE'

export type ModelCatalogEntry = {
    id: string
    modelKey: string
    version: number
    provider: string
    providerModelId: string
    displayName: string
    lifecycle: ModelLifecycle
    maxInputTokens: number
    maxOutputTokens: number
    capabilities: ModelCapability[]
    inputModalities: ModelModality[]
    outputModalities: ModelModality[]
    retentionStatus: ModelRetentionStatus
    retentionDays: number | null
    trainingUseStatus: ModelTrainingUseStatus
    pricingStatus: ModelPricingStatus
    pricingComplete: boolean
    inputUsdPer1mTokens: string | null
    cachedInputUsdPer1mTokens: string | null
    cacheWriteInputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    extraPricingJson: string
    pricingVersion: string | null
    effectiveFrom: string
    source: ModelCatalogSource
    createdByUserId: string
    createdAt: string
}

export type RuntimeRoutingMode =
    typeof RUNTIME_ROUTING_MODES[number]

export type RuntimeDataRetentionStatus =
    typeof RUNTIME_DATA_RETENTION_STATUSES[number]

export type RuntimeHealthStatus =
    typeof RUNTIME_HEALTH_STATUSES[number]

export type RuntimePricingStatus =
    typeof RUNTIME_PRICING_STATUSES[number]

export type InputAccountingVersion =
    typeof INPUT_ACCOUNTING_VERSIONS[number]

export type RuntimeModelStatus = {
    provider: string
    model: string
    enabled: boolean
    routingMode: RuntimeRoutingMode
    maxInputTokens: number
    maxOutputTokens: number
    toolsSupported: boolean
    visionSupported: boolean
    structuredOutputSupported: boolean
    dataRetentionStatus: RuntimeDataRetentionStatus
    healthStatus: RuntimeHealthStatus
    pricingStatus: RuntimePricingStatus
    inputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    pricingVersion: string | null
}


export type RuntimeModelProbeStatus =
    typeof RUNTIME_MODEL_PROBE_STATUSES[number]

export type RuntimeModelProbe = {
    provider: string
    model: string
    status: RuntimeModelProbeStatus
    checkedAt: string
    latencyMs: number
    httpStatus: number | null
    message: string
}

export type OrganizationModelPolicy = {
    configured: boolean
    id: string | null
    organizationId: string
    version: number
    enabled: boolean
    allowModelKeys: string[]
    denyModelKeys: string[]
    defaultModelKey: string | null
    maxInputTokens: number | null
    maxOutputTokens: number | null
    maxRequestCostUsd: string | null
    monthlyBudgetUsd: string | null
    budgetEnforcement: BudgetEnforcement
    requireCompletePricing: boolean
    requireNoTraining: boolean
    requireZeroDataRetention: boolean
    createdByUserId: string | null
    createdAt: string | null
}

export type ModelRouteDecision = {
    id: string
    organizationId: string
    userId: string
    chatId: string
    chatTurnId: string | null
    clientRequestId: string
    requestContentHash: string
    requestedModelKey: string | null
    selectedCatalogEntryId: string | null
    selectedCatalogVersion: number | null
    selectedModelKey: string | null
    selectedProvider: string | null
    selectedProviderModelId: string | null
    policyId: string | null
    policyVersion: number | null
    requiredCapabilities: ModelCapability[]
    inputAccountingVersion: InputAccountingVersion | null
    additionalInputUnitUpperBound: number | null
    estimatedInputTokens: number | null
    estimatedOutputTokens: number | null
    estimatedMaxCostUsd: string | null
    monthlyBudgetUsd: string | null
    monthlySpentUsd: string | null
    monthlyProjectedUsd: string | null
    monthlyCostKnown: boolean
    monthlyCostState: MonthlyCostState
    budgetEnforcement: BudgetEnforcement | null
    budgetExceeded: boolean
    pricingComplete: boolean
    outcome: ModelRouteOutcome
    reason: ModelRouteReason
    decisionIntegrityVersion: 1 | 2 | 3
    decisionSha256: string
    createdAt: string
}

export type CreateModelCatalogVersionRequest = {
    modelKey: string
    provider: string
    providerModelId: string
    displayName: string
    lifecycle: ModelLifecycle
    maxInputTokens: number
    maxOutputTokens: number
    capabilities: ModelCapability[]
    inputModalities: ModelModality[]
    outputModalities: ModelModality[]
    retentionStatus: ModelRetentionStatus
    retentionDays: number | null
    trainingUseStatus: ModelTrainingUseStatus
    pricingStatus: ModelPricingStatus
    pricingComplete: boolean
    inputUsdPer1mTokens: string | null
    cachedInputUsdPer1mTokens: string | null
    cacheWriteInputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    extraPricingJson: string
    pricingVersion: string | null
    effectiveFrom: string | null
    expectedPreviousVersion: number
}

export type CreateOrganizationModelPolicyVersionRequest = {
    expectedPreviousVersion: number
    enabled: boolean
    allowModelKeys: string[]
    denyModelKeys: string[]
    defaultModelKey: string | null
    maxInputTokens: number | null
    maxOutputTokens: number | null
    maxRequestCostUsd: string | null
    monthlyBudgetUsd: string | null
    budgetEnforcement: BudgetEnforcement
    requireCompletePricing: boolean
    requireNoTraining: boolean
    requireZeroDataRetention: boolean
}

export type ModelPolicyPreviewItem = {
    modelKey: string
    catalogVersion: number
    provider: string
    providerModelId: string
    lifecycle: ModelLifecycle
    outcome: ModelRouteOutcome
    reason: ModelRouteReason | null
    effectiveInputLimit: number
    effectiveOutputLimit: number
    pricingComplete: boolean
    estimatedMaxCostUsd: string | null
    monthlyBudgetUsd: string | null
    monthlyProjectedUsd: string | null
    monthlyCostKnown: boolean
    budgetExceeded: boolean
}

export type ModelPolicyPreview = {
    organizationId: string
    basePolicyVersion: number
    enabled: boolean
    evaluatedAt: string
    runtimeProvider: string
    runtimeModel: string
    automaticOutcome: ModelRouteOutcome
    automaticReason: ModelRouteReason
    automaticModelKey: string | null
    executableModelCount: number
    wouldLockOutOrganization: boolean
    models: ModelPolicyPreviewItem[]
}
