// frontend/src/features/usage/ui/usageBreakdownChartData.ts
import type { UsageAmounts } from '../api/usageApi'
import type { UsageReportTab } from '../lib/adminUsageFilterState'

export type BreakdownReportTab = Exclude<UsageReportTab, 'daily'>
export type BreakdownChartDatum = {
    label: string
    shortLabel: string
    input: number | null
    output: number | null
    sourceIndex: number
    value?: number | null
    exactValue?: string | null
}
export type BreakdownChartData = {
    data: BreakdownChartDatum[]
    available: boolean
    totalRows: number
    shownRows: number
}

const MAX_DISPLAYED_ROWS = 12
const MAX_SAFE = BigInt(Number.MAX_SAFE_INTEGER)

/**
 * The chart receives real usage rows, including partial-token evidence.
 * Partial tokens are intentionally excluded from bars and ranking: their
 * presence in this type prevents chart tests from inventing narrower records
 * that cannot represent the production data contract.
 */
type BreakdownInput = Pick<
    UsageAmounts,
    | 'inputTokens'
    | 'outputTokens'
    | 'totalTokens'
    | 'partialInputTokens'
    | 'partialOutputTokens'
    | 'partialTotalTokens'
> & {
    userEmail?: string
    model?: string
    organizationName?: string
    coverage?: UsageAmounts['coverage']
    costUsd?: string | null
}

function count(value: string): bigint | null {
    return /^\d+$/.test(value) ? BigInt(value) : null
}

function labelFor(tab: BreakdownReportTab, row: BreakdownInput): string {
    if (tab === 'organizations') return row.organizationName || 'Неизвестная организация'
    if (tab === 'models') return row.model || 'Неизвестная модель'
    if (tab === 'users') return row.userEmail || 'Неизвестный пользователь'
    const email = row.userEmail || 'Неизвестный пользователь'
    return row.model ? `${email} · ${row.model}` : email
}

export type RankingMetric = 'tokens' | 'cost' | 'responses' | 'coverage' | 'total'

export function compactTick(value: number): string {
    return Math.abs(value) >= 1000
        ? new Intl.NumberFormat('ru-RU', {notation: 'compact', maximumFractionDigits: 1}).format(value)
        : new Intl.NumberFormat('ru-RU', {maximumSignificantDigits: 4}).format(value)
}

export function coveragePercent(coverage: UsageAmounts['coverage'] | undefined): string | null {
    if (!coverage) return null
    const {assistantMessages, partialUsageMessages, missingUsageMessages, unpricedMessages, pricingFailedMessages} = coverage
    if (!assistantMessages || assistantMessages === '0' || [partialUsageMessages, missingUsageMessages, unpricedMessages, pricingFailedMessages].some(v => v === null)) return null
    const count = BigInt(assistantMessages)
    const tokenMissing = BigInt(partialUsageMessages!) + BigInt(missingUsageMessages!)
    const costMissing = BigInt(unpricedMessages!) + BigInt(pricingFailedMessages!)
    const missing = tokenMissing > costMissing ? tokenMissing : costMissing
    if (missing > count) return null
    return ((count - missing) * 100n / count).toString()
}

export function exactMetric(row: BreakdownInput, metric: RankingMetric): string | null {
    if (row.coverage?.assistantMessages === '0') return null
    if (metric === 'cost') return row.coverage?.pricedMessages === '0' && row.coverage.freeMessages === '0' ? null : row.costUsd ?? null
    if (metric === 'responses') return row.coverage?.assistantMessages ?? null
    if (metric === 'coverage') return coveragePercent(row.coverage)
    return row.totalTokens
}

// Exact decimal ranking (including very small USD values).
function compareExact(a: string | null, b: string | null): number {
    if (a !== null && !/^\d+(?:\.\d+)?$/.test(a)) a = null
    if (b !== null && !/^\d+(?:\.\d+)?$/.test(b)) b = null
    if (a === null || b === null) return a === b ? 0 : a === null ? 1 : -1
    const [aw, af = ''] = a.split('.'), [bw, bf = ''] = b.split('.')
    const scale = Math.max(af.length, bf.length)
    const x = BigInt(aw + af.padEnd(scale, '0')), y = BigInt(bw + bf.padEnd(scale, '0'))
    return x === y ? 0 : x > y ? -1 : 1
}

/**
 * Visualizes ONLY server-returned rows. Does not guess missing pages or
 * multiply partial-usage tokens into confirmed totals. BigInt is used for
 * ranking and checking IEEE-754 boundaries; no imprecise bar is drawn.
 */
export function buildUsageBreakdownChartData(
    tab: BreakdownReportTab,
    rows: readonly BreakdownInput[],
    limit = MAX_DISPLAYED_ROWS,
    metric: RankingMetric = 'tokens',
    serverSorted = false,
): BreakdownChartData {
    const prepared = rows.map((row, index) => ({
        index,
        row,
        input: count(row.inputTokens),
        output: count(row.outputTokens),
        total: count(row.totalTokens),
        metric: exactMetric(row, metric),
    }))
    const selected = [...prepared]
        .sort((a, b) => serverSorted ? a.index - b.index
            : compareExact(a.metric, b.metric) || a.index - b.index)
        .slice(0, Math.max(1, Math.min(50, limit)))
    const available = selected.every(item =>
        metric !== 'tokens' && metric !== 'total'
            ? item.metric === null || (Number.isFinite(Number(item.metric)) && Number(item.metric) <= Number.MAX_SAFE_INTEGER)
            :
        item.input !== null
        && item.output !== null
        && item.total !== null
        && item.input <= MAX_SAFE
        && item.output <= MAX_SAFE
        && item.total <= MAX_SAFE,
    )
    if (!available) {
        return { data: [], available: false, totalRows: rows.length, shownRows: 0 }
    }
    const data = selected.map(({ row, input, output, index, metric: exactValue }) => {
        const label = labelFor(tab, row)
        return {
            label,
            shortLabel: label.length > 25 ? `${label.slice(0, 24)}…` : label,
            input: row.coverage?.assistantMessages === '0' ? null : Number(input),
            output: row.coverage?.assistantMessages === '0' ? null : Number(output),
            sourceIndex: index,
            value: exactValue === null ? null : Number(exactValue),
            exactValue,
        }
    })
    return { data, available: true, totalRows: rows.length, shownRows: data.length }
}
