// frontend/src/features/usage/api/usageProviderApi.ts
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import { buildQueryString } from '../../../shared/api/query'
import { contractError, expectRecord } from '../../../shared/api/runtime'
import { assertDateRange, toUtcExclusiveEndOfDayIso, toUtcStartOfDayIso } from '../../../shared/lib/date'
import type { ExportFilter } from './usageExportApi'

const COUNTS = ['attempts', 'unresolved', 'unknownCost', 'unknownUsage', 'input', 'output'] as const
export type ProviderRow = Record<typeof COUNTS[number], string> & {provider: string; knownCostUsd: string}

export function parseProviderRows(value: unknown): ProviderRow[] {
    if (!Array.isArray(value) || value.length > 1000) throw contractError('Некорректный список провайдеров')
    const seen = new Set<string>()
    return value.map(raw => {
        const row = expectRecord(raw, 'provider')
        if (typeof row.provider !== 'string' || !row.provider || row.provider.length > 32
            || seen.has(row.provider) || typeof row.knownCostUsd !== 'string'
            || !/^(0|[1-9]\d*)(\.\d+)?$/.test(row.knownCostUsd) || row.knownCostUsd.length > 128) {
            throw contractError('Некорректная запись провайдера')
        }
        seen.add(row.provider)
        const counts = {} as Record<typeof COUNTS[number], string>
        for (const key of COUNTS) {
            const count = row[key]
            if (typeof count !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(count)
                || BigInt(count) > 9223372036854775807n) throw contractError('Некорректный счётчик')
            counts[key] = count
        }
        const attempts = BigInt(counts.attempts)
        if (!attempts || BigInt(counts.unresolved) > attempts
            || BigInt(counts.unknownCost) > attempts || BigInt(counts.unknownUsage) > attempts
            || BigInt(counts.unresolved) > BigInt(counts.unknownCost)
            || BigInt(counts.unresolved) > BigInt(counts.unknownUsage)) {
            throw contractError('Несогласованные счётчики провайдера')
        }
        return {provider: row.provider, knownCostUsd: row.knownCostUsd, ...counts}
    })
}

export async function getUsageProviders(filter: ExportFilter, signal: AbortSignal): Promise<ProviderRow[]> {
    assertDateRange(filter.dateFrom, filter.dateTo, 366)
    const query = buildQueryString({
        dateFrom: toUtcStartOfDayIso(filter.dateFrom), dateTo: toUtcExclusiveEndOfDayIso(filter.dateTo),
        organizationId: filter.organizationId, model: filter.model || null, userId: filter.userId || null,
    })
    const report = expectRecord(await apiRequest<unknown>(
        `/api/admin/usage/physical-attempts/providers${query}`, {signal, timeoutMs: API_TIMEOUTS.report},
    ), 'providerReport')
    if (report.from !== toUtcStartOfDayIso(filter.dateFrom).replace('.000Z', 'Z')
        || report.to !== toUtcExclusiveEndOfDayIso(filter.dateTo).replace('.000Z', 'Z')
        || report.organizationId !== (filter.organizationId?.toLowerCase() ?? null)
        || report.model !== (filter.model || null)
        || report.userId !== (filter.userId?.toLowerCase() || null)
        || report.aggregationBasis !== 'PHYSICAL_ATTEMPT_STARTED_AT') {
        throw contractError('Сервер вернул другой период или область отчёта')
    }
    return parseProviderRows(report.rows)
}
