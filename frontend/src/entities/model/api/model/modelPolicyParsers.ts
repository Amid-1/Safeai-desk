// frontend/src/entities/model/api/model/modelPolicyParsers.ts
// Назначение: проверка настроек и предпросмотра политик организаций.

import type { OrganizationModelPolicy, ModelPolicyPreview, ModelPolicyPreviewItem } from './modelTypes'
import { MODEL_LIFECYCLES, BUDGET_ENFORCEMENTS, MODEL_ROUTE_OUTCOMES, MODEL_ROUTE_REASONS, ALLOWED_REASONS, DENIED_REASONS } from './modelEnums'
import { requireObject, requireArray, requireNonBlankString, requireBoolean, requireEnum, requirePositiveInteger, requireNonNegativeInteger, optionalPositiveInteger, optionalDecimalString, requireModelKey, optionalModelKey, requireModelKeyArray, requireUuid, optionalUuid, requireInstant, optionalInstant, optionalEnum } from './modelWire'
import { validateOrganizationPolicySemantics } from './modelSemantics'

export function parseOrganizationModelPolicy(
    value: unknown,
    path = 'modelPolicy',
): OrganizationModelPolicy {
    const raw = requireObject(value, path)

    const policy: OrganizationModelPolicy = {
        configured: requireBoolean(
            raw.configured,
            `${path}.configured`,
        ),
        id: optionalUuid(
            raw.id,
            `${path}.id`,
        ),
        organizationId: requireUuid(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        version: requireNonNegativeInteger(
            raw.version,
            `${path}.version`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        allowModelKeys: requireModelKeyArray(
            raw.allowModelKeys,
            `${path}.allowModelKeys`,
        ),
        denyModelKeys: requireModelKeyArray(
            raw.denyModelKeys,
            `${path}.denyModelKeys`,
        ),
        defaultModelKey: optionalModelKey(
            raw.defaultModelKey,
            `${path}.defaultModelKey`,
        ),
        maxInputTokens: optionalPositiveInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: optionalPositiveInteger(
            raw.maxOutputTokens,
            `${path}.maxOutputTokens`,
        ),
        maxRequestCostUsd: optionalDecimalString(
            raw.maxRequestCostUsd,
            `${path}.maxRequestCostUsd`,
        ),
        monthlyBudgetUsd: optionalDecimalString(
            raw.monthlyBudgetUsd,
            `${path}.monthlyBudgetUsd`,
        ),
        budgetEnforcement: requireEnum(
            raw.budgetEnforcement,
            BUDGET_ENFORCEMENTS,
            `${path}.budgetEnforcement`,
        ),
        requireCompletePricing: requireBoolean(
            raw.requireCompletePricing,
            `${path}.requireCompletePricing`,
        ),
        requireNoTraining: requireBoolean(
            raw.requireNoTraining,
            `${path}.requireNoTraining`,
        ),
        requireZeroDataRetention: requireBoolean(
            raw.requireZeroDataRetention,
            `${path}.requireZeroDataRetention`,
        ),
        createdByUserId: optionalUuid(
            raw.createdByUserId,
            `${path}.createdByUserId`,
        ),
        createdAt: optionalInstant(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }

    validateOrganizationPolicySemantics(policy, path)
    return policy
}

export function parseModelPolicyPreview(
    value: unknown,
    path = 'modelPolicyPreview',
): ModelPolicyPreview {
    const raw = requireObject(value, path)
    const models = requireArray(raw.models, `${path}.models`)
        .map((item, index): ModelPolicyPreviewItem => {
            const model = requireObject(
                item,
                `${path}.models[${index}]`,
            )
            return {
                modelKey: requireModelKey(
                    model.modelKey,
                    `${path}.models[${index}].modelKey`,
                ),
                catalogVersion: requirePositiveInteger(
                    model.catalogVersion,
                    `${path}.models[${index}].catalogVersion`,
                ),
                provider: requireNonBlankString(
                    model.provider,
                    `${path}.models[${index}].provider`,
                ),
                providerModelId: requireNonBlankString(
                    model.providerModelId,
                    `${path}.models[${index}].providerModelId`,
                ),
                lifecycle: requireEnum(
                    model.lifecycle,
                    MODEL_LIFECYCLES,
                    `${path}.models[${index}].lifecycle`,
                ),
                outcome: requireEnum(
                    model.outcome,
                    MODEL_ROUTE_OUTCOMES,
                    `${path}.models[${index}].outcome`,
                ),
                reason: optionalEnum(
                    model.reason,
                    MODEL_ROUTE_REASONS,
                    `${path}.models[${index}].reason`,
                ),
                effectiveInputLimit: requirePositiveInteger(
                    model.effectiveInputLimit,
                    `${path}.models[${index}].effectiveInputLimit`,
                ),
                effectiveOutputLimit: requirePositiveInteger(
                    model.effectiveOutputLimit,
                    `${path}.models[${index}].effectiveOutputLimit`,
                ),
                pricingComplete: requireBoolean(
                    model.pricingComplete,
                    `${path}.models[${index}].pricingComplete`,
                ),
                estimatedMaxCostUsd: optionalDecimalString(
                    model.estimatedMaxCostUsd,
                    `${path}.models[${index}].estimatedMaxCostUsd`,
                ),
                monthlyBudgetUsd: optionalDecimalString(
                    model.monthlyBudgetUsd,
                    `${path}.models[${index}].monthlyBudgetUsd`,
                ),
                monthlyProjectedUsd: optionalDecimalString(
                    model.monthlyProjectedUsd,
                    `${path}.models[${index}].monthlyProjectedUsd`,
                ),
                monthlyCostKnown: requireBoolean(
                    model.monthlyCostKnown,
                    `${path}.models[${index}].monthlyCostKnown`,
                ),
                budgetExceeded: requireBoolean(
                    model.budgetExceeded,
                    `${path}.models[${index}].budgetExceeded`,
                ),
            }
        })

    const result: ModelPolicyPreview = {
        organizationId: requireUuid(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        basePolicyVersion: requireNonNegativeInteger(
            raw.basePolicyVersion,
            `${path}.basePolicyVersion`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        evaluatedAt: requireInstant(
            raw.evaluatedAt,
            `${path}.evaluatedAt`,
        ),
        runtimeProvider: requireNonBlankString(
            raw.runtimeProvider,
            `${path}.runtimeProvider`,
        ),
        runtimeModel: requireNonBlankString(
            raw.runtimeModel,
            `${path}.runtimeModel`,
        ),
        automaticOutcome: requireEnum(
            raw.automaticOutcome,
            MODEL_ROUTE_OUTCOMES,
            `${path}.automaticOutcome`,
        ),
        automaticReason: requireEnum(
            raw.automaticReason,
            MODEL_ROUTE_REASONS,
            `${path}.automaticReason`,
        ),
        automaticModelKey: optionalModelKey(
            raw.automaticModelKey,
            `${path}.automaticModelKey`,
        ),
        executableModelCount: requireNonNegativeInteger(
            raw.executableModelCount,
            `${path}.executableModelCount`,
        ),
        wouldLockOutOrganization: requireBoolean(
            raw.wouldLockOutOrganization,
            `${path}.wouldLockOutOrganization`,
        ),
        models,
    }

    const modelKeys = models.map(item => item.modelKey)
    if (new Set(modelKeys).size !== modelKeys.length) {
        throw new Error(`${path}.models содержит duplicate modelKey`)
    }

    for (const [index, item] of models.entries()) {
        if (item.outcome === 'ALLOWED' && item.reason !== null) {
            throw new Error(
                `${path}.models[${index}] ALLOWED не должен иметь denial reason`,
            )
        }
        if (item.outcome === 'DENIED'
            && (item.reason === null
                || !DENIED_REASONS.includes(item.reason as never))) {
            throw new Error(
                `${path}.models[${index}] DENIED требует denial reason`,
            )
        }
    }

    const automaticReasonSet =
        result.automaticOutcome === 'ALLOWED'
            ? ALLOWED_REASONS
            : DENIED_REASONS
    if (!automaticReasonSet.includes(result.automaticReason as never)) {
        throw new Error(
            `${path}.automaticReason не соответствует automaticOutcome`,
        )
    }
    if (result.automaticOutcome === 'ALLOWED'
        && result.automaticModelKey === null) {
        throw new Error(
            `${path}.automaticModelKey обязателен для ALLOWED`,
        )
    }

    if (result.executableModelCount
        !== models.filter(item => item.outcome === 'ALLOWED').length) {
        throw new Error(
            `${path}.executableModelCount не соответствует models`,
        )
    }
    if (result.wouldLockOutOrganization
        !== (result.enabled && result.executableModelCount === 0)) {
        throw new Error(
            `${path}.wouldLockOutOrganization противоречит preview`,
        )
    }
    return result
}
