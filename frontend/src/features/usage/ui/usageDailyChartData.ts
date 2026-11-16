// frontend/src/features/usage/ui/usageDailyChartData.ts
import type { UsageDailySummary } from '../api/usageApi'
import { formatIsoDate } from '../lib/adminUsagePresentation'

export function chartNumber(value: string): number | null {
    const number = Number(value)
    // Chart geometry uses IEEE-754; confirmed integer counts must stay safe.
    return Number.isFinite(number) && number >= 0 && number <= Number.MAX_SAFE_INTEGER
        ? number : null
}

export function buildDailySeries(rows: UsageDailySummary[], dateFrom?: string, dateTo?: string) {
    const byDate = new Map(rows.map(row => [row.usageDate, row]))
    let dates = [...byDate.keys()].sort()
    const start = Date.parse(dateFrom ?? '')
    const end = Date.parse(dateTo ?? '')
    const count = (end - start) / 86400000 + 1
    if (Number.isInteger(count) && count >= 1 && count <= 366) {
        dates = Array.from({ length: count }, (_, i) =>
            new Date(start + i * 86400000).toISOString().slice(0, 10))
    }
    return dates.map(date => {
        const row = byDate.get(date)
        return {
            date: formatIsoDate(date),
            input: row ? chartNumber(row.inputTokens) : null,
            output: row ? chartNumber(row.outputTokens) : null,
            total: row ? chartNumber(row.totalTokens) : null,
            exactCost: row?.costUsd ?? null,
            cost: row?.costUsd == null ? null : chartNumber(row.costUsd),
        }
    })
}
