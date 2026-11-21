// frontend/src/features/usage/ui/usageDailyChartData.ts
import type { UsageDailySummary } from '../api/usageApi'
import { formatIsoDate } from '../lib/adminUsagePresentation'
import { sumDecimalAmounts } from './usageMetrics'
import { coveragePercent, exactMetric } from './usageBreakdownChartData'

export type DailyGrouping = 'day' | 'week' | 'month'

export function chartNumber(value: string): number | null {
    const number = Number(value)
    // Chart geometry uses IEEE-754; confirmed integer counts must stay safe.
    return Number.isFinite(number) && number >= 0 && number <= Number.MAX_SAFE_INTEGER
        ? number : null
}

export function buildDailySeries(rows: UsageDailySummary[], dateFrom?: string, dateTo?: string, grouping: DailyGrouping = 'day') {
    const byDate = new Map(rows.map(row => [row.usageDate, row]))
    let dates = [...byDate.keys()].sort()
    const start = Date.parse(dateFrom ?? '')
    const end = Date.parse(dateTo ?? '')
    const count = (end - start) / 86400000 + 1
    if (Number.isInteger(count) && count >= 1 && count <= 366) {
        dates = Array.from({ length: count }, (_, i) =>
            new Date(start + i * 86400000).toISOString().slice(0, 10))
    }
    const groups = new Map<string, string[]>()
    for (const date of dates) {
        const day = new Date(date + 'T00:00:00Z')
        const weekStart = new Date(day.getTime() - ((day.getUTCDay() + 6) % 7) * 86400000).toISOString().slice(0, 10)
        const key = grouping === 'day' ? date : grouping === 'week' ? weekStart : date.slice(0, 7)
        const group = groups.get(key) ?? []
        group.push(date)
        groups.set(key, group)
    }
    return [...groups.values()].map(days => {
        const start = days[0], end = days[days.length - 1]
        const entries = days.flatMap(date => byDate.has(date) ? [byDate.get(date)!] : [])
        const sum = (selector: (row: UsageDailySummary) => string | null) => {
            const values = entries.map(selector)
            return values.length && values.every(v => v !== null) ? values.reduce<bigint>((n, v) => n + BigInt(v!), 0n).toString() : null
        }
        const knownCosts = entries.map(row => exactMetric(row, 'cost')).filter((v): v is string => v !== null)
        const cost = knownCosts.length ? sumDecimalAmounts(knownCosts) : null
        const input = sum(r => r.inputTokens), output = sum(r => r.outputTokens), total = sum(r => r.totalTokens)
        const responses = sum(r => r.coverage.assistantMessages)
        const coverage = coveragePercent(entries.length ? {
            ...entries[0].coverage,
            assistantMessages: responses,
            partialUsageMessages: sum(r => r.coverage.partialUsageMessages),
            missingUsageMessages: sum(r => r.coverage.missingUsageMessages),
            unpricedMessages: sum(r => r.coverage.unpricedMessages),
            pricingFailedMessages: sum(r => r.coverage.pricingFailedMessages),
        } : undefined)
        const number = (value: string | null) => value === null ? null : chartNumber(value)
        return {
            date: start === end ? formatIsoDate(start) : formatIsoDate(start) + ' — ' + formatIsoDate(end),
            start, end, records: entries.length, days: days.length,
            exactInput: input, exactOutput: output, exactTotal: total,
            input: number(input), output: number(output), total: number(total),
            exactCost: cost, cost: number(cost),
            exactResponses: responses, responses: number(responses),
            coverage: number(coverage), exactCoverage: coverage,
        }
    })
}
