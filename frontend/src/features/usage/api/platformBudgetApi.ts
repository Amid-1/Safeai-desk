// frontend/src/features/usage/api/platformBudgetApi.ts
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import { contractError, expectRecord } from '../../../shared/api/runtime'

const keys = ['total', 'enabled', 'within', 'nearLimit', 'exceeded', 'noLimit', 'unknown', 'disabled', 'unconfigured'] as const
type CountKey = typeof keys[number]
export type PlatformBudgetSummary = Record<CountKey, number> & {
    periodStart: string; periodEnd: string; asOf: string
}

export function parsePlatformBudgetSummary(value: unknown): PlatformBudgetSummary {
    const record = expectRecord(value, 'platformBudgetSummary')
    const counts = Object.fromEntries(keys.map(key => {
        const count = record[key]
        if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0)
            throw contractError('Некорректное количество в сводке бюджетов')
        return [key, count]
    })) as Record<CountKey, number>
    const instant = (key: string) => {
        const raw = record[key]
        if (typeof raw !== 'string' || !Number.isFinite(Date.parse(raw)))
            throw contractError('Некорректный период сводки бюджетов')
        return raw
    }
    const periodStart = instant('periodStart'), periodEnd = instant('periodEnd'), asOf = instant('asOf')
    if (counts.enabled > counts.total || keys.slice(2).reduce((sum, key) => sum + counts[key], 0) !== counts.total
        || Date.parse(periodStart) > Date.parse(asOf) || Date.parse(asOf) >= Date.parse(periodEnd))
        throw contractError('Несогласованная сводка бюджетов')
    return {...counts, periodStart, periodEnd, asOf}
}

export async function getPlatformBudgetSummary(signal: AbortSignal) {
    return parsePlatformBudgetSummary(await apiRequest<unknown>('/api/admin/models/policies/budget-summary', {
        method: 'GET', signal, timeoutMs: API_TIMEOUTS.report,
    }))
}
