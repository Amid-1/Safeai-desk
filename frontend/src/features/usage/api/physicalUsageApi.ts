// frontend/src/features/usage/api/physicalUsageApi.ts
import { API_TIMEOUTS, apiRequest } from '../../../shared/api/http'
import { buildQueryString, uuidPathSegment } from '../../../shared/api/query'
import { contractError, expectBoolean, expectInstant, expectRecord, expectNullableUuid } from '../../../shared/api/runtime'
import { toUtcStartOfDayIso, toUtcExclusiveEndOfDayIso, assertDateRange } from '../../../shared/lib/date'

const COUNTERS = [
    'physicalAttempts', 'startedAttempts', 'ambiguousAttempts', 'knownExecutedAttempts',
    'knownNotExecutedAttempts', 'knownRejectedAttempts', 'costUnverifiableAttempts',
    'usageUnverifiableAttempts', 'knownInputTokens', 'knownOutputTokens', 'plans',
    'costMatchedPlans', 'costMismatchPlans', 'unverifiablePlans',
] as const

export type PhysicalUsageReport = Record<(typeof COUNTERS)[number], string> & {
    from: string
    to: string
    organizationId: string | null
    aggregationBasis: 'PHYSICAL_ATTEMPT_STARTED_AT'
    planSelectionBasis: 'PLANS_WITH_PHYSICAL_START_IN_RANGE'
    currency: 'USD'
    knownCostUsd: string
    costComplete: boolean
    usageComplete: boolean
    fullyReconciled: boolean
}

export type PhysicalUsageScope = {
    dateFrom: string
    dateTo: string
    organizationId: string | null
    expectedOrganizationId: string | null
}

export function parsePhysicalUsageReport(value: unknown): PhysicalUsageReport {
    const row = expectRecord(value, 'physicalUsageReport')
    const from = expectInstant(row.from, 'from')
    const to = expectInstant(row.to, 'to')
    const organizationId = expectNullableUuid(row.organizationId, 'organizationId')
    if (Date.parse(from) >= Date.parse(to)
        || row.aggregationBasis !== 'PHYSICAL_ATTEMPT_STARTED_AT'
        || row.planSelectionBasis !== 'PLANS_WITH_PHYSICAL_START_IN_RANGE'
        || row.currency !== 'USD') {
        throw contractError('Некорректный период или база физического отчёта')
    }
    const counts = {} as Record<(typeof COUNTERS)[number], string>
    for (const key of COUNTERS) {
        const raw = row[key]
        if (typeof raw !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(raw)
            || BigInt(raw) > 9223372036854775807n) {
            throw contractError(`Некорректный точный счётчик: ${key}`)
        }
        counts[key] = raw
    }
    const knownCostUsd = row.knownCostUsd
    if (typeof knownCostUsd !== 'string' || knownCostUsd.length > 128
        || !/^(0|[1-9]\d*)(\.\d+)?$/.test(knownCostUsd)) {
        throw contractError('Некорректная точная стоимость физических вызовов')
    }
    const costComplete = expectBoolean(row.costComplete, 'costComplete')
    const usageComplete = expectBoolean(row.usageComplete, 'usageComplete')
    const fullyReconciled = expectBoolean(row.fullyReconciled, 'fullyReconciled')
    const n = (key: (typeof COUNTERS)[number]) => BigInt(counts[key])
    const unresolved = n('startedAttempts') + n('ambiguousAttempts')
    const attempts = n('physicalAttempts')
    const plans = n('plans')
    if (unresolved + n('knownExecutedAttempts') + n('knownNotExecutedAttempts')
        + n('knownRejectedAttempts') !== attempts
        || n('costUnverifiableAttempts') < unresolved || n('costUnverifiableAttempts') > attempts
        || n('usageUnverifiableAttempts') < unresolved || n('usageUnverifiableAttempts') > attempts
        || costComplete !== (n('costUnverifiableAttempts') === 0n)
        || usageComplete !== (n('usageUnverifiableAttempts') === 0n)
        || n('costMatchedPlans') + n('costMismatchPlans') + n('unverifiablePlans') !== plans
        || plans > attempts
        || fullyReconciled !== (plans > 0n && n('costMismatchPlans') === 0n && n('unverifiablePlans') === 0n)
        || (attempts === 0n && (n('knownInputTokens') !== 0n || n('knownOutputTokens') !== 0n
            || !/^0(?:\.0+)?$/.test(knownCostUsd)))) {
        throw contractError('Несогласованные счётчики физического отчёта')
    }
    return {
        from, to, organizationId, ...counts, knownCostUsd, costComplete, usageComplete, fullyReconciled,
        aggregationBasis: 'PHYSICAL_ATTEMPT_STARTED_AT',
        planSelectionBasis: 'PLANS_WITH_PHYSICAL_START_IN_RANGE', currency: 'USD',
    }
}

export async function getPhysicalUsageReport(
    scope: PhysicalUsageScope, signal: AbortSignal,
): Promise<PhysicalUsageReport> {
    assertDateRange(scope.dateFrom, scope.dateTo, 366)
    const from = toUtcStartOfDayIso(scope.dateFrom)
    const to = toUtcExclusiveEndOfDayIso(scope.dateTo)
    const organizationId = scope.organizationId ? uuidPathSegment(scope.organizationId) : null
    const query = buildQueryString({dateFrom: from, dateTo: to, organizationId})
    const value = await apiRequest<unknown>(`/api/admin/usage/physical-attempts/report${query}`, {
        method: 'GET', signal, timeoutMs: API_TIMEOUTS.report,
    })
    const report = parsePhysicalUsageReport(value)
    if (report.from !== from.replace('.000Z', 'Z') || report.to !== to.replace('.000Z', 'Z')
        || report.organizationId !== (scope.expectedOrganizationId?.toLowerCase() ?? null)) {
        throw contractError('Сервер вернул отчёт для другой области или периода')
    }
    return report
}
