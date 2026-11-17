import type {PolicyDraft} from './modelTypes'
import {parseOptionalDecimal} from './modelValues'

export const MAX_POLICY_TOKEN_LIMIT = 2_147_483_647

export function parsePolicyTokenLimit(value: string, field: string): number | null {
    const normalized = value.trim()
    if (!normalized) return null
    const parsed = Number(normalized)
    if (!/^\d+$/.test(normalized) || !Number.isSafeInteger(parsed)
        || parsed < 1 || parsed > MAX_POLICY_TOKEN_LIMIT) {
        throw new Error(`${field}: введите целое число от 1 до 2 147 483 647, без разделителей.`)
    }
    return parsed
}

/** Localized input only; the wire contract remains an exact decimal string in USD. */
export function parsePolicyMoney(value: string, field: string): string | null {
    return parseOptionalDecimal(value.trim().replace(',', '.'), field)
}

export type PolicyNumericField = 'maxInputTokens' | 'maxOutputTokens'
    | 'maxRequestCostUsd' | 'monthlyBudgetUsd'

export const POLICY_NUMERIC_LABELS: Record<PolicyNumericField, string> = {
    maxInputTokens: 'Максимум входных токенов',
    maxOutputTokens: 'Максимум выходных токенов',
    maxRequestCostUsd: 'Лимит на один запрос',
    monthlyBudgetUsd: 'Месячный бюджет',
}

export function validatePolicyNumericFields(draft: PolicyDraft): Partial<Record<PolicyNumericField, string>> {
    const errors: Partial<Record<PolicyNumericField, string>> = {}
    for (const field of Object.keys(POLICY_NUMERIC_LABELS) as PolicyNumericField[]) {
        try {
            const parser = field === 'maxInputTokens' || field === 'maxOutputTokens'
                ? parsePolicyTokenLimit : parsePolicyMoney
            parser(draft[field], POLICY_NUMERIC_LABELS[field])
        } catch (error) {
            errors[field] = error instanceof Error ? error.message : 'Некорректное значение.'
        }
    }
    return errors
}
