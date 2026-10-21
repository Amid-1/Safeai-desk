// frontend/src/entities/model/api/model/modelSemantics.ts
// Назначение: проверка бизнес-инвариантов и доказательств маршрутизации.

import type { ModelCatalogEntry, ModelRouteDecision, OrganizationModelPolicy } from './modelTypes'
import { ALLOWED_REASONS, DENIED_REASONS } from './modelEnums'

export function validateRouteSemantics(
    decision: ModelRouteDecision,
    path: string,
): void {
    const reasonSet =
        decision.outcome === 'ALLOWED'
            ? ALLOWED_REASONS
            : DENIED_REASONS

    if (!reasonSet.includes(
        decision.reason as never,
    )) {
        throw new Error(
            `${path}.reason не соответствует outcome`,
        )
    }

    if (decision.outcome === 'ALLOWED') {
        if (
            decision.chatTurnId === null
            || decision.selectedModelKey === null
            || decision.selectedProvider === null
            || decision.selectedProviderModelId === null
            || decision.estimatedInputTokens === null
            || decision.estimatedOutputTokens === null
        ) {
            throw new Error(
                'ALLOWED decision не содержит executable metadata',
            )
        }
    } else if (decision.chatTurnId !== null) {
        throw new Error(
            'DENIED decision не должен содержать chatTurnId',
        )
    }

    if (decision.reason === 'MODEL_NOT_FOUND'
        && decision.decisionIntegrityVersion >= 2
        && (
            decision.selectedCatalogEntryId !== null
            || decision.selectedCatalogVersion !== null
            || decision.selectedProvider !== null
            || decision.selectedProviderModelId !== null
        )) {
        throw new Error(
            'MODEL_NOT_FOUND decision содержит physical target',
        )
    }

    if (decision.decisionIntegrityVersion === 3
        && (
            decision.inputAccountingVersion === null
            || decision.additionalInputUnitUpperBound === null
            || decision.additionalInputUnitUpperBound < 0
        )) {
        throw new Error(
            'V3 decision не содержит input accounting provenance',
        )
    }

    if (decision.decisionIntegrityVersion < 3
        && (
            decision.inputAccountingVersion !== null
            || decision.additionalInputUnitUpperBound !== null
        )) {
        throw new Error(
            'V1/V2 decision неожиданно содержит V3 provenance',
        )
    }

    if (decision.monthlyCostState === 'NOT_EVALUATED'
        && decision.monthlyBudgetUsd !== null) {
        throw new Error(
            'NOT_EVALUATED monthly cost не должен иметь monthlyBudgetUsd',
        )
    }

    if (decision.monthlyCostState === 'KNOWN'
        && !decision.monthlyCostKnown) {
        throw new Error(
            'KNOWN monthly cost требует monthlyCostKnown=true',
        )
    }

    if (decision.monthlyCostState === 'UNKNOWN'
        && decision.monthlyCostKnown) {
        throw new Error(
            'UNKNOWN monthly cost требует monthlyCostKnown=false',
        )
    }
}

export function validateOrganizationPolicySemantics(
    policy: OrganizationModelPolicy,
    path: string,
): void {
    const overlap = policy.allowModelKeys.filter(
        key => policy.denyModelKeys.includes(key),
    )
    if (overlap.length > 0) {
        throw new Error(`${path} allow/deny lists пересекаются`)
    }
    if (policy.defaultModelKey !== null
        && policy.denyModelKeys.includes(policy.defaultModelKey)) {
        throw new Error(`${path}.defaultModelKey находится в denylist`)
    }
    if (policy.defaultModelKey !== null
        && policy.allowModelKeys.length > 0
        && !policy.allowModelKeys.includes(policy.defaultModelKey)) {
        throw new Error(`${path}.defaultModelKey отсутствует в allowlist`)
    }

    if (!policy.configured) {
        const validSynthetic =
            policy.id === null
            && policy.version === 0
            && !policy.enabled
            && policy.allowModelKeys.length === 0
            && policy.denyModelKeys.length === 0
            && policy.defaultModelKey === null
            && policy.maxInputTokens === null
            && policy.maxOutputTokens === null
            && policy.maxRequestCostUsd === null
            && policy.monthlyBudgetUsd === null
            && policy.budgetEnforcement === 'SOFT'
            && !policy.requireCompletePricing
            && !policy.requireNoTraining
            && !policy.requireZeroDataRetention
            && policy.createdByUserId === null
            && policy.createdAt === null
        if (!validSynthetic) {
            throw new Error(`${path} содержит некорректное unconfigured состояние`)
        }
        return
    }

    if (policy.id === null
        || policy.version <= 0
        || policy.createdByUserId === null
        || policy.createdAt === null) {
        throw new Error(`${path} содержит неполное configured состояние`)
    }
}

export function validateCatalogSemantics(
    entry: ModelCatalogEntry,
    path: string,
): void {
    if (entry.inputModalities.length === 0 || entry.outputModalities.length === 0) {
        throw new Error(`${path}.modalities не должны быть пустыми`)
    }
    if (entry.outputModalities.includes('IMAGE')) {
        throw new Error(`${path} не поддерживает IMAGE output modality`)
    }
    const hasVision = entry.capabilities.includes('VISION')
    const hasImageInput = entry.inputModalities.includes('IMAGE')
    if (hasVision !== hasImageInput) {
        throw new Error(`${path} VISION и IMAGE input должны объявляться совместно`)
    }
    if (entry.retentionStatus === 'ZERO_DATA_RETENTION'
        && entry.retentionDays !== null
        && entry.retentionDays !== 0) {
        throw new Error(`${path} ZERO_DATA_RETENTION требует retentionDays=0 или null`)
    }

    const noExtraPricing = entry.extraPricingJson === '{}'
    if (entry.pricingStatus === 'UNPRICED') {
        if (entry.pricingComplete
            || entry.inputUsdPer1mTokens !== null
            || entry.cachedInputUsdPer1mTokens !== null
            || entry.cacheWriteInputUsdPer1mTokens !== null
            || entry.outputUsdPer1mTokens !== null
            || !noExtraPricing) {
            throw new Error(`${path} содержит некорректный UNPRICED pricing`)
        }
    } else if (entry.pricingStatus === 'FREE') {
        const zero = (value: string | null) => value === null || /^0+(?:\.0+)?$/.test(value)
        if (!entry.pricingComplete
            || entry.inputUsdPer1mTokens === null
            || !zero(entry.inputUsdPer1mTokens)
            || entry.outputUsdPer1mTokens === null
            || !zero(entry.outputUsdPer1mTokens)
            || !zero(entry.cachedInputUsdPer1mTokens)
            || !zero(entry.cacheWriteInputUsdPer1mTokens)
            || !noExtraPricing) {
            throw new Error(`${path} содержит некорректный FREE pricing`)
        }
    } else if (entry.pricingStatus === 'CONFIGURED') {
        if (!entry.pricingComplete
            || entry.inputUsdPer1mTokens === null
            || entry.outputUsdPer1mTokens === null
            || entry.pricingVersion === null
            || !noExtraPricing) {
            throw new Error(`${path} содержит некорректный CONFIGURED pricing`)
        }
    } else if (entry.pricingComplete) {
        throw new Error(`${path} INCOMPLETE не может быть pricingComplete=true`)
    }
}
