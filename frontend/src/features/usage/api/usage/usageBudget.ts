// frontend/src/features/usage/api/usage/usageBudget.ts
import { API_TIMEOUTS, apiRequest } from '../../../../shared/api/http'
import { uuidPathSegment } from '../../../../shared/api/query'
import { contractError, expectBoolean, expectInstant, expectRecord, expectString, expectUuid } from '../../../../shared/api/runtime'
import { count, nonNegativeDecimal } from './usageValidation'

const statuses = ['UNCONFIGURED', 'DISABLED', 'NO_LIMIT', 'KNOWN', 'EXCEEDED', 'UNKNOWN', 'EXCEEDED_LOWER_BOUND'] as const
type BudgetStatus = typeof statuses[number]

export function parseBudgetStatus(value: unknown) {
    const record = expectRecord(value, 'budget')
    const status = expectString(record.status, 'budget.status') as BudgetStatus
    const enforcement = expectString(record.enforcement, 'budget.enforcement')
    if (!statuses.includes(status) || !['SOFT', 'HARD'].includes(enforcement)) {
        throw contractError('Некорректное состояние бюджета')
    }
    const decimal = (field: string) => {
        const raw = record[field]
        if (typeof raw !== 'string') throw contractError(`Некорректная сумма: ${field}`)
        const parsed = nonNegativeDecimal(raw, field)
        if (parsed === null) throw contractError(`Некорректная сумма: ${field}`)
        return parsed
    }
    const nullable = (field: string) => record[field] === null ? null : decimal(field)
    const instant = (field: string) => {
        const raw = expectInstant(record[field], field)
        // UTC may be serialized as Z or the equivalent explicit zero offset.
        // Retain nanosecond precision and normalize only the zone spelling.
        if (!/(Z|\+00:00)$/.test(raw)) throw contractError('Некорректный период бюджета')
        return raw.replace(/\+00:00$/, 'Z')
    }
    const periodStart = instant('periodStart')
    const periodEnd = instant('periodEnd')
    const asOf = instant('asOf')
    if (Date.parse(periodStart) > Date.parse(asOf) || Date.parse(asOf) >= Date.parse(periodEnd)) {
        throw contractError('Некорректный период бюджета')
    }
    const remainingUsd = nullable('remainingUsd')
    const monthlyBudgetUsd = nullable('monthlyBudgetUsd')
    const unknownCommittedCostCount = count(record.unknownCommittedCostCount, 'unknownCommittedCostCount')
    const policyEnabled = expectBoolean(record.policyEnabled, 'policyEnabled')
    if ((remainingUsd !== null) !== (status === 'KNOWN' || status === 'EXCEEDED')
        || (remainingUsd !== null && (!policyEnabled || monthlyBudgetUsd === null || unknownCommittedCostCount !== '0'))) {
        throw contractError('Некорректный остаток бюджета')
    }
    return {
        organizationId: expectUuid(record.organizationId, 'organizationId'),
        policyVersion: count(record.policyVersion, 'policyVersion'),
        policyEnabled, enforcement, periodStart, periodEnd, asOf, status,
        monthlyBudgetUsd, remainingUsd, unknownCommittedCostCount,
        knownActualUsd: decimal('knownActualUsd'),
        reservedUsd: decimal('reservedUsd'),
        knownCommittedUsd: decimal('knownCommittedUsd'),
    }
}

export type OrganizationBudgetStatus = ReturnType<typeof parseBudgetStatus>

export async function getOrganizationBudgetStatus(organizationId: string, signal: AbortSignal) {
    return parseBudgetStatus(await apiRequest<unknown>(
        `/api/admin/models/policies/${uuidPathSegment(organizationId)}/budget-status`,
        { method: 'GET', signal, timeoutMs: API_TIMEOUTS.report },
    ))
}
