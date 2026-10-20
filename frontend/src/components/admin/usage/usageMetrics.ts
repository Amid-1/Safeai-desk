import type { UsageAmounts } from '../../../api/usageApi'

/** All financial amounts are decimal strings; never sum money with Number. */
export function sumDecimalAmounts(values: readonly string[]): string {
    if (values.length === 0) return '0'
    let scale = 0
    for (const value of values) {
        if (!/^\d+(?:\.\d+)?$/.test(value)) {
            throw new Error('Invalid decimal amount')
        }
        scale = Math.max(scale, (value.split('.')[1] ?? '').length)
    }
    const total = values.reduce((sum, value) => {
        const [whole, fraction = ''] = value.split('.')
        return sum + BigInt(whole + fraction.padEnd(scale, '0'))
    }, 0n)
    if (scale === 0) return String(total)
    const digits = total.toString().padStart(scale + 1, '0')
    return `${digits.slice(0, -scale)}.${digits.slice(-scale)}`
        .replace(/\.?0+$/, '') || '0'
}

export function summarizeUsageRows(rows: readonly UsageAmounts[]) {
    let input = 0n
    let output = 0n
    let total = 0n
    let partial = 0n
    let completeRows = 0
    let pricedRows = 0
    let unknownCostRows = 0
    const knownCosts: string[] = []
    const currencies = new Set<string>()

    for (const row of rows) {
        input += BigInt(row.inputTokens)
        output += BigInt(row.outputTokens)
        total += BigInt(row.totalTokens)
        partial += BigInt(row.partialTotalTokens)
        if (row.coverage.usageComplete === true
            && row.coverage.pricingComplete === true) completeRows += 1
        if (row.costUsd !== null) {
            knownCosts.push(row.costUsd)
            currencies.add(row.currency)
            pricedRows += 1
        } else {
            unknownCostRows += 1
        }
    }
    return {
        input: input.toString(),
        output: output.toString(),
        total: total.toString(),
        partial: partial.toString(),
        completeRows,
        pricedRows,
        unknownCostRows,
        currency: currencies.size === 1 ? [...currencies][0] : null,
        knownCost: currencies.size <= 1 ? sumDecimalAmounts(knownCosts) : null,
        rowCount: rows.length,
    }
}
