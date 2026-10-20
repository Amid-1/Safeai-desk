import type { UsageAmounts } from '../../../api/usageApi'
import type { UsageReportTab } from '../../../pages/adminUsageFilterState'

export type BreakdownReportTab = Exclude<UsageReportTab, 'daily'>
export type BreakdownChartDatum = {
    label: string
    shortLabel: string
    input: number
    output: number
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
}

function count(value: string): bigint | null {
    return /^\d+$/.test(value) ? BigInt(value) : null
}

function labelFor(tab: BreakdownReportTab, row: BreakdownInput): string {
    if (tab === 'models') return row.model || 'Неизвестная модель'
    if (tab === 'users') return row.userEmail || 'Неизвестный пользователь'
    const email = row.userEmail || 'Неизвестный пользователь'
    return row.model ? `${email} · ${row.model}` : email
}

/**
 * Visualizes ONLY server-returned rows. Does not guess missing pages or
 * multiply partial-usage tokens into confirmed totals. BigInt is used for
 * ranking and checking IEEE-754 boundaries; no imprecise bar is drawn.
 */
export function buildUsageBreakdownChartData(
    tab: BreakdownReportTab,
    rows: readonly BreakdownInput[],
): BreakdownChartData {
    const prepared = rows.map((row, index) => ({
        index,
        row,
        input: count(row.inputTokens),
        output: count(row.outputTokens),
        total: count(row.totalTokens),
    }))
    const available = prepared.every(item =>
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
    const selected = [...prepared]
        .sort((a, b) => {
            if (a.total! !== b.total!) return a.total! > b.total! ? -1 : 1
            return a.index - b.index
        })
        .slice(0, MAX_DISPLAYED_ROWS)
    const data = selected.map(({ row, input, output }) => {
        const label = labelFor(tab, row)
        return {
            label,
            shortLabel: label.length > 25 ? `${label.slice(0, 24)}…` : label,
            input: Number(input),
            output: Number(output),
        }
    })
    return { data, available: true, totalRows: rows.length, shownRows: data.length }
}
