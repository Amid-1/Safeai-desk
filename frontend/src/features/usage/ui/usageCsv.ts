// frontend/src/features/usage/ui/usageCsv.ts
import type { UsageAmounts, UsageDailySummary, UsageModelSummary, UsageSummary, UsageUserSummary } from '../api/usageApi'
import type { UsageReportTab } from '../lib/adminUsageFilterState'
import type { UsageOrganizationSummary } from '../api/usage/usageTypes'

function safeCell(value: string): string {
    const raw = String(value)
    const formula = /^[\p{Cc}\p{Cf}\p{Z}\s]*[=+\-@]/u.test(raw)
    const printable = raw.replace(/[\p{Cc}]/gu, ' ')
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
        row.coverage.assistantMessages ?? '', row.coverage.completedResponses ?? '',
        row.coverage.refusedResponses ?? '', row.coverage.incompleteResponses ?? '',
        row.coverage.failedMessages ?? '', row.coverage.unclassifiedResponses ?? '',
        row.coverage.availableUsageMessages ?? '', row.coverage.partialUsageMessages ?? '',
        row.coverage.missingUsageMessages ?? '', row.coverage.usageNotApplicableMessages ?? '',
        row.coverage.pricedMessages ?? '', row.coverage.freeMessages ?? '',
        row.coverage.pricingNotApplicableMessages ?? '',
    ]
}

const HEADER = [
    'Входные токены (подтвержденные)', 'Выходные токены (подтвержденные)',
    'Всего токенов (подтвержденные)', 'Входные токены partial',
    'Выходные токены partial', 'Всего токенов partial',
    'Известная стоимость', 'Валюта', 'Полнота токенов',
    'Полнота стоимости', 'Сообщений без цены', 'Ошибок цены',
    'Ответов AI', 'Завершено', 'Отказано', 'Неполных ответов', 'Ошибок ответа',
    'Неклассифицированных ответов', 'Полный учёт токенов', 'Частичный учёт токенов',
    'Токены неизвестны', 'Учёт токенов неприменим', 'С известной ценой',
    'Бесплатных ответов', 'Цена неприменима',
]

export type UsageCsvContext = {
    dateFrom: string
    dateTo: string
    organizationId: string | null
    model: string
    userId: string
    budgetStatus: string
    order: string
    page: number | null
    scope: 'displayed' | 'filtered'
}

const CONTEXT_HEADER = [
    'База учёта', 'Начало периода UTC', 'Конец периода UTC включительно',
    'Область организации', 'Фильтр модели', 'Фильтр пользователя', 'Фильтр бюджета',
    'Сортировка', 'Область выгрузки', 'Страница', 'Строк в файле',
]

export function serializeCsvRows(rows: string[][]): string {
    return rows.map(row => row.map(safeCell).join(';')).join('\r\n')
}

/** Pure serializer: current loaded rows only; never fetches other pages or tenants. */
export function serializeUsageCsv(
    tab: UsageReportTab | 'organizations', rows: UsageAmounts[], context?: UsageCsvContext,
): string {
    let leading: string[]
    let values: string[][]
    if (tab === 'organizations') {
        leading = ['ID организации', 'Организация', 'Наличие данных']
        values = (rows as UsageOrganizationSummary[]).map(row => [
            row.organizationId, row.organizationName,
            row.coverage.assistantMessages === '0' ? 'Нет данных' : 'Есть данные',
            ...(row.coverage.assistantMessages === '0' ? HEADER.map(() => '') : coverageCells(row)),
        ])
    } else if (tab === 'summary') {
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
    if (context) {
        const cells = [
            'Сохранённые записи ответов AI', context.dateFrom, context.dateTo,
            context.organizationId ?? 'Все организации', context.model, context.userId, context.budgetStatus,
            context.order, context.scope === 'filtered' ? 'По фильтрам' : 'Текущий отчёт',
            context.page === null ? '' : String(context.page + 1), String(rows.length),
        ]
        return serializeCsvRows([[...leading, ...HEADER, ...CONTEXT_HEADER], ...values.map(row => [...row, ...cells])])
    }
    return serializeCsvRows([[...leading, ...HEADER], ...values])
}

export function downloadUsageCsv(
    tab: UsageReportTab | 'organizations', rows: UsageAmounts[],
    scope: 'displayed' | 'filtered' = 'displayed', context?: UsageCsvContext,
): void {
    if (rows.length === 0) return
    const csv = serializeUsageCsv(tab, rows, context)
    downloadCsvText(csv, `safeai-usage-${tab}-${scope}.csv`)
}

export function downloadCsvText(csv: string, filename: string): void {
    const objectUrl = URL.createObjectURL(new Blob([`\uFEFF${csv}`], {type: 'text/csv;charset=utf-8'}))
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = filename
    document.body.appendChild(link)
    try { link.click() } finally {
        link.remove()
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
    }
}
