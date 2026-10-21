// frontend/src/features/usage/ui/usageCsv.ts
import type { UsageAmounts, UsageDailySummary, UsageModelSummary, UsageSummary, UsageUserSummary } from '../api/usageApi'
import type { UsageReportTab } from '../lib/adminUsageFilterState'

function safeCell(value: string): string {
    const raw = String(value)
    const formula = /^[\p{Cc}\p{Cf}\p{Z}\s]*[=+\-@]/u.test(raw)
    const printable = raw.replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ')
    const neutral = formula ? `\u0027${printable}` : printable
    return `"${neutral.replaceAll('"', '""')}"`
}

function coverageCells(row: UsageAmounts): string[] {
    return [
        row.inputTokens, row.outputTokens, row.totalTokens,
        row.partialInputTokens, row.partialOutputTokens, row.partialTotalTokens,
        row.costUsd ?? '', row.currency,
        row.coverage.usageComplete === null ? 'НЕИЗВЕСТНО' : row.coverage.usageComplete ? 'ПОЛНО' : 'ЧАСТИЧНО',
        row.coverage.pricingComplete === null ? 'НЕИЗВЕСТНО' : row.coverage.pricingComplete ? 'ПОЛНО' : 'ЧАСТИЧНО',
        row.coverage.unpricedMessages ?? '', row.coverage.pricingFailedMessages ?? '',
    ]
}

const HEADER = [
    'Входные токены (подтвержденные)', 'Выходные токены (подтвержденные)',
    'Всего токенов (подтвержденные)', 'Входные токены partial',
    'Выходные токены partial', 'Всего токенов partial',
    'Известная стоимость', 'Валюта', 'Полнота токенов',
    'Полнота стоимости', 'Сообщений без цены', 'Ошибок цены',
]

/** Pure serializer: current loaded rows only; never fetches other pages or tenants. */
export function serializeUsageCsv(tab: UsageReportTab, rows: UsageAmounts[]): string {
    let leading: string[]
    let values: string[][]
    if (tab === 'summary') {
        leading = ['ID пользователя', 'Email пользователя', 'Модель']
        values = (rows as UsageSummary[]).map(row => [row.userId, row.userEmail, row.model, ...coverageCells(row)])
    } else if (tab === 'users') {
        leading = ['ID пользователя', 'Email пользователя']
        values = (rows as UsageUserSummary[]).map(row => [row.userId, row.userEmail, ...coverageCells(row)])
    } else if (tab === 'models') {
        leading = ['Модель']
        values = (rows as UsageModelSummary[]).map(row => [row.model, ...coverageCells(row)])
    } else {
        leading = ['Дата UTC']
        values = (rows as UsageDailySummary[]).map(row => [row.usageDate, ...coverageCells(row)])
    }
    return [ [...leading, ...HEADER], ...values]
        .map(row => row.map(safeCell).join(';')).join('\r\n')
}

export function downloadUsageCsv(tab: UsageReportTab, rows: UsageAmounts[]): void {
    if (rows.length === 0) return
    const csv = serializeUsageCsv(tab, rows)
    const objectUrl = URL.createObjectURL(new Blob([`\uFEFF${csv}`], {type: 'text/csv;charset=utf-8'}))
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = `safeai-usage-${tab}-displayed.csv`
    document.body.appendChild(link)
    try { link.click() } finally {
        link.remove()
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
    }
}
