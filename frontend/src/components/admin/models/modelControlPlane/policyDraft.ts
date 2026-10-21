// frontend/src/components/admin/models/modelControlPlane/policyDraft.ts
import type {
    CreateOrganizationModelPolicyVersionRequest, OrganizationModelPolicy,
} from '../../../../api/modelApi'
import type { PolicyDraft } from './modelTypes'
import { parseModelKeyList, normalizeModelKey } from './modelKeys'
import { parseOptionalPositiveInteger, parseOptionalDecimal } from './modelValues'


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
            policy.maxRequestCostUsd ?? '',
        monthlyBudgetUsd:
            policy.monthlyBudgetUsd ?? '',
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

    return {
        expectedPreviousVersion,
        enabled: draft.enabled,
        allowModelKeys,
        denyModelKeys,
        defaultModelKey,
        maxInputTokens:
            parseOptionalPositiveInteger(
                draft.maxInputTokens,
                'Максимум входных токенов',
            ),
        maxOutputTokens:
            parseOptionalPositiveInteger(
                draft.maxOutputTokens,
                'Максимум выходных токенов',
            ),
        maxRequestCostUsd:
            parseOptionalDecimal(
                draft.maxRequestCostUsd,
                'Максимальная стоимость запроса',
            ),
        monthlyBudgetUsd:
            parseOptionalDecimal(
                draft.monthlyBudgetUsd,
                'Месячный бюджет',
            ),
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
