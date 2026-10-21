// frontend/src/components/admin/models/modelControlPlane/modelTypes.ts
import type {
    ModelCapability, ModelLifecycle, ModelModality, ModelPricingStatus,
    ModelRetentionStatus, ModelTrainingUseStatus, BudgetEnforcement,
} from '../../../../api/modelApi'

export type CatalogDraft = {
    modelKey: string
    provider: string
    providerModelId: string
    displayName: string
    lifecycle: ModelLifecycle
    maxInputTokens: string
    maxOutputTokens: string
    capabilities: ModelCapability[]
    inputModalities: ModelModality[]
    outputModalities: ModelModality[]
    retentionStatus: ModelRetentionStatus
    retentionDays: string
    trainingUseStatus: ModelTrainingUseStatus
    pricingStatus: ModelPricingStatus
    pricingComplete: boolean
    inputUsdPer1mTokens: string
    cachedInputUsdPer1mTokens: string
    cacheWriteInputUsdPer1mTokens: string
    outputUsdPer1mTokens: string
    extraPricingJson: string
    pricingVersion: string
    effectiveFrom: string
}


export type PolicyDraft = {
    enabled: boolean
    allowModelKeys: string
    denyModelKeys: string
    defaultModelKey: string
    maxInputTokens: string
    maxOutputTokens: string
    maxRequestCostUsd: string
    monthlyBudgetUsd: string
    budgetEnforcement: BudgetEnforcement
    requireCompletePricing: boolean
    requireNoTraining: boolean
    requireZeroDataRetention: boolean
}
