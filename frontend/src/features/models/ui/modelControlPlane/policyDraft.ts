// frontend/src/features/models/ui/modelControlPlane/policyDraft.ts
import type {
    CreateOrganizationModelPolicyVersionRequest, OrganizationModelPolicy,
} from '../../../../entities/model/api/modelApi'
import type { PolicyDraft } from './modelTypes'
import { parseModelKeyList, normalizeModelKey } from './modelKeys'
import { parsePolicyTokenLimit, parsePolicyMoney } from './policyNumericFields'


export function createPolicyDraft(
    policy: OrganizationModelPolicy,
): PolicyDraft {
    return {
        enabled: policy.enabled,
        allowModelKeys:
            policy.allowModelKeys.join('\n'),
        denyModelKeys:
            policy.denyModelKeys.join('\n'),
        defaultModelKey:
            policy.defaultModelKey ?? '',
        maxInputTokens:
            policy.maxInputTokens == null
                ? ''
                : String(policy.maxInputTokens),
        maxOutputTokens:
            policy.maxOutputTokens == null
                ? ''
                : String(policy.maxOutputTokens),
        maxRequestCostUsd:
            policy.rubBudget ? policy.rubBudget.maxRequestCostRub ?? '' : policy.maxRequestCostUsd ?? '',
        monthlyBudgetUsd:
            policy.rubBudget ? policy.rubBudget.monthlyBudgetRub ?? '' : policy.monthlyBudgetUsd ?? '',
        ...(policy.rubBudget ? {rubQuote: {
            rubPerUsd: policy.rubBudget.rubPerUsd, quoteDate: policy.rubBudget.quoteDate,
            effectiveDate: policy.rubBudget.effectiveDate, fetchedAt: policy.rubBudget.fetchedAt,
            source: policy.rubBudget.source,
        }} : {}),
        budgetEnforcement:
            policy.budgetEnforcement,
        requireCompletePricing:
            policy.requireCompletePricing,
        requireNoTraining:
            policy.requireNoTraining,
        requireZeroDataRetention:
            policy.requireZeroDataRetention,
    }
}

export function buildPolicyRequest(
    draft: PolicyDraft,
    expectedPreviousVersion: number,
): CreateOrganizationModelPolicyVersionRequest {
    const allowModelKeys =
        parseModelKeyList(
            draft.allowModelKeys,
        )

    const denyModelKeys =
        parseModelKeyList(
            draft.denyModelKeys,
        )

    const overlap =
        allowModelKeys.filter(
            (key) =>
                denyModelKeys.includes(key),
        )

    if (overlap.length > 0) {
        throw new Error(
            `Одна и та же модель указана и в разрешённых, и в запрещённых: ${overlap.join(', ')}`,
        )
    }

    const defaultModelKey =
        draft.defaultModelKey.trim()
            ? normalizeModelKey(
                draft.defaultModelKey,
            )
            : null

    if (
        defaultModelKey
        && denyModelKeys.includes(
            defaultModelKey,
        )
    ) {
        throw new Error(
            'Модель по умолчанию не может находиться в списке запрещённых.',
        )
    }

    if (
        defaultModelKey
        && allowModelKeys.length > 0
        && !allowModelKeys.includes(
            defaultModelKey,
        )
    ) {
        throw new Error(
            'Если список разрешённых моделей заполнен, модель по умолчанию должна входить в него.',
        )
    }

    const maxCost = parsePolicyMoney(draft.maxRequestCostUsd, 'Максимальная стоимость запроса')
    const monthly = parsePolicyMoney(draft.monthlyBudgetUsd, 'Месячный бюджет')
    const rubBudget = draft.rubQuote && (maxCost !== null || monthly !== null) ? {
        maxRequestCostRub: maxCost, monthlyBudgetRub: monthly,
        rubPerUsd: draft.rubQuote.rubPerUsd, quoteDate: draft.rubQuote.quoteDate,
        effectiveDate: draft.rubQuote.effectiveDate,
    } : undefined
    return {
        expectedPreviousVersion,
        enabled: draft.enabled,
        allowModelKeys,
        denyModelKeys,
        defaultModelKey,
        maxInputTokens:
            parsePolicyTokenLimit(
                draft.maxInputTokens,
                'Максимум входных токенов',
            ),
        maxOutputTokens:
            parsePolicyTokenLimit(
                draft.maxOutputTokens,
                'Максимум выходных токенов',
            ),
        maxRequestCostUsd: rubBudget ? null : maxCost,
        monthlyBudgetUsd: rubBudget ? null : monthly,
        ...(rubBudget ? {rubBudget} : {}),
        budgetEnforcement:
            draft.budgetEnforcement,
        requireCompletePricing:
            draft.requireCompletePricing,
        requireNoTraining:
            draft.requireNoTraining,
        requireZeroDataRetention:
            draft.requireZeroDataRetention,
    }
}
